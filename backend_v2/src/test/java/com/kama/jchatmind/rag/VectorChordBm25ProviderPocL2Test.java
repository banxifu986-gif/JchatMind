package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "g2.native-bm25.poc.l2", matches = "true")
class VectorChordBm25ProviderPocL2Test {

    private static final String TABLE = "bm25_catalog.g2_vchord_provider_poc";
    private static final String TITLE_INDEX = "bm25_catalog.g2_vchord_provider_poc_title_idx";
    private static final String BODY_INDEX = "bm25_catalog.g2_vchord_provider_poc_body_idx";
    private static final String FROZEN_TABLE = "bm25_catalog.g2_vchord_frozen_poc";
    private static final String FROZEN_TITLE_INDEX = "bm25_catalog.g2_vchord_frozen_poc_title_idx";
    private static final String FROZEN_BODY_INDEX = "bm25_catalog.g2_vchord_frozen_poc_body_idx";
    private static final String SCALE_TABLE = "bm25_catalog.g2_vchord_scale_poc";
    private static final int SCALE_CANDIDATE_COUNT = 5_000;
    private final IsolatedPostgresContainer database = new IsolatedPostgresContainer(
            "g2-vchord-poc", "g2vchord", "sha256:8c106fde572fb799217dcacb01b6f869af693322069bc134dbd6341d0c175abd"
    );

    @Test
    void keepsHardScopeFiltersInTheNativeTokenVectorTopNAndSurvivesBackupRestore() {
        database.assertIsolation();
        assertTrue(database.sql("SHOW server_version").trim().startsWith("14.22"));
        assertEquals("0.3.0", database.sql("SELECT extversion FROM pg_extension WHERE extname = 'vchord_bm25'").trim());
        database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        try {
            createFixtureAndIndex();

            assertEquals("1", database.sql(scopedTitleQuery()).trim());
            String normalizedBodyPath = IsolatedPostgresContainer.normalizeContentPath(" RAG> BM25  >Body ");
            assertEquals("RAG > BM25 > Body", normalizedBodyPath);
            assertEquals("2", database.sql(scopedBodyQuery(normalizedBodyPath)).trim());
            String bodyPlan = database.sql("SET search_path = bm25_catalog, pg_catalog, public; EXPLAIN (COSTS OFF) " + scopedBodySelect(normalizedBodyPath));
            assertTrue(bodyPlan.contains("Limit"));
            assertTrue(bodyPlan.contains("kb_id = 'kb-authorized'"));
            assertTrue(bodyPlan.contains("source_name = 'architecture.md'"));
            assertTrue(bodyPlan.contains("source_type = 'md'"));
            assertTrue(bodyPlan.contains("content_path = 'RAG > BM25 > Body'"));
            assertTrue(database.sql("SELECT pg_get_indexdef('" + TITLE_INDEX + "'::regclass)").contains("USING bm25"));
            assertTrue(database.sql("SELECT pg_get_indexdef('" + BODY_INDEX + "'::regclass)").contains("USING bm25"));

            database.sql("DELETE FROM " + TABLE + " WHERE id = 2");
            assertEquals("", database.sql(scopedBodyQuery(normalizedBodyPath)).trim(), "删除后不得出现 stale hit");
            database.sql("INSERT INTO " + TABLE + " VALUES (2, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Body', '{90:1}'::bm25_catalog.bm25vector, '{30:1,40:1}'::bm25_catalog.bm25vector)");
            database.sql("DROP INDEX " + TITLE_INDEX);
            database.sql("DROP INDEX " + BODY_INDEX);
            createIndex();
            assertEquals("2", database.sql(scopedBodyQuery(normalizedBodyPath)).trim(), "重建后正文通道应恢复同一 scope 内 gold");

            String backup = database.dumpTable(TABLE);
            assertTrue(backup.contains("CREATE INDEX g2_vchord_provider_poc_title_idx"));
            assertTrue(backup.contains("CREATE INDEX g2_vchord_provider_poc_body_idx"));
            database.sql("DROP TABLE " + TABLE);
            assertEquals("", database.sql("SELECT to_regclass('" + TABLE + "')").trim());
            database.restore(backup);

            assertEquals("1", database.sql(scopedTitleQuery()).trim());
            assertEquals("2", database.sql(scopedBodyQuery(normalizedBodyPath)).trim());
            assertTrue(database.sql("SELECT pg_get_indexdef('" + TITLE_INDEX + "'::regclass)").contains("USING bm25"));
            assertTrue(database.sql("SELECT pg_get_indexdef('" + BODY_INDEX + "'::regclass)").contains("USING bm25"));
            database.writeEvidenceReport("g2-vchord-provider-poc-l2.json", Map.of(
                    "provider", "vchord_bm25",
                    "containerImageId", database.imageId(),
                    "databaseVersion", database.sql("SHOW server_version").trim(),
                    "extensionVersion", database.sql("SELECT extversion FROM pg_extension WHERE extname = 'vchord_bm25'").trim(),
                    "bodyScopePlan", bodyPlan,
                    "backupRestoreTitleIndex", database.sql("SELECT pg_get_indexdef('" + TITLE_INDEX + "'::regclass)").trim(),
                    "backupRestoreBodyIndex", database.sql("SELECT pg_get_indexdef('" + BODY_INDEX + "'::regclass)").trim(),
                    "defaultPlannerOnly", true
            ));
        } finally {
            database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        }
    }

    @Test
    void exposesThatTokenVectorBm25NeedsAProviderSpecificProjection() {
        database.assertIsolation();
        assertEquals("bm25_catalog.bm25vector", database.sql("""
                SELECT n.nspname || '.' || t.typname
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = 'bm25_catalog'
                  AND t.typname = 'bm25vector'
                """).trim());
    }

    @Test
    void demonstratesThatGlobalTokenVectorTopNDoesNotRepresentTheAuthorizedScope() {
        database.assertIsolation();
        database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        try {
            createFixtureAndIndex();

            String unscopedRanking = database.sql(unscopedBodyRankingQuery()).trim();
            String outsideTopN = unscopedRanking.lines().findFirst().orElseThrow();
            assertTrue(candidateId(outsideTopN) > 100, "全局 Top-N 必须实际返回范围外干扰项");
            assertTrue(candidateScore(outsideTopN) < candidateScore(unscopedRanking, 2), "范围外干扰项必须拥有更高 native BM25 排序分数");
        } finally {
            database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        }
    }

    @Test
    void retrievesFrozenChineseCodeTitleApiPathAndPdfCasesWithExplicitTokenizerProjection() throws Exception {
        database.assertIsolation();
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json"
        );
        assertEquals("g2-pre-bm25-v1", dataset.manifest().datasetId());
        assertEquals(9, dataset.cases().size());
        database.sql("DROP TABLE IF EXISTS " + FROZEN_TABLE + " CASCADE");
        try {
            FrozenFixture fixture = frozenFixture(dataset);
            createFrozenFixture(fixture.chunks());
            for (String caseId : List.of(
                    "g2-pre-bm25-v1-001",
                    "g2-pre-bm25-v1-002",
                    "g2-pre-bm25-v1-003",
                    "g2-pre-bm25-v1-006",
                    "g2-pre-bm25-v1-009"
            )) {
                RagEvaluationCase evaluationCase = dataset.cases().stream()
                        .filter(item -> caseId.equals(item.caseId()))
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        evaluationCase.goldChunkIds().get(0),
                        database.sql(frozenQuery(evaluationCase, fixture.tokenIds())).trim(),
                        () -> "冻结语料的 VectorChord BM25 未命中 " + caseId
                );
            }
            database.writeEvidenceReport("g2-vchord-frozen-projection-l2.json", Map.of(
                    "provider", "vchord_bm25",
                    "containerImageId", database.imageId(),
                    "datasetId", dataset.manifest().datasetId(),
                    "tokenizer", "RetrievableTitleLexicalizer",
                    "projection", "application_side_token_id_bm25vector",
                    "verifiedCaseIds", new LinkedHashSet<>(List.of(
                            "g2-pre-bm25-v1-001",
                            "g2-pre-bm25-v1-002",
                            "g2-pre-bm25-v1-003",
                            "g2-pre-bm25-v1-006",
                            "g2-pre-bm25-v1-009"
                    ))
            ));
        } finally {
            database.sql("DROP TABLE IF EXISTS " + FROZEN_TABLE + " CASCADE");
        }
    }

    @Test
    void usesDefaultBm25IndexForFiveThousandScopedCandidates() {
        database.assertIsolation();
        database.sql("DROP TABLE IF EXISTS " + SCALE_TABLE + " CASCADE");
        try {
            createScaleFixtureAndIndex();

            assertEquals(Integer.toString(SCALE_CANDIDATE_COUNT), database.sql("SELECT count(*) FROM " + SCALE_TABLE).trim());
            String unscopedRanking = database.sql(scaleUnscopedBodyRankingQuery()).trim();
            String outsideTopN = unscopedRanking.lines().findFirst().orElseThrow();
            assertTrue(candidateId(outsideTopN) > 100, "5,000 行全局 Top-N 必须实际返回范围外干扰项");
            assertTrue(candidateScore(outsideTopN) < candidateScore(unscopedRanking, 1), "5,000 行范围外干扰项必须拥有更高 native BM25 排序分数");
            assertEquals("1", database.sql(scaleScopedBodyQuery()).trim());
            List<String> plannerEvidence = database.sqlCommands(
                    """
                    SET search_path = bm25_catalog, pg_catalog, public;
                    SELECT current_setting('enable_seqscan') || '|' || current_setting('enable_indexscan');
                    """,
                    "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + scaleScopedBodySelect()
            ).lines().toList();
            assertTrue(plannerEvidence.size() > 1);
            String[] plannerSettings = plannerEvidence.get(0).split("\\|", -1);
            assertEquals(2, plannerSettings.length);
            assertEquals("on", plannerSettings[0]);
            assertEquals("on", plannerSettings[1]);
            String queryPlan = String.join("\n", plannerEvidence.subList(1, plannerEvidence.size()));
            assertTrue(queryPlan.contains("Index Scan using g2_vchord_scale_poc_body_idx"));
            assertTrue(!queryPlan.contains("Seq Scan"));
            assertTrue(queryPlan.contains("kb_id = 'kb-authorized'"));
            assertTrue(queryPlan.contains("source_name = 'architecture.md'"));
            assertTrue(queryPlan.contains("source_type = 'md'"));
            assertTrue(queryPlan.contains("content_path = 'RAG > BM25 > Body'"));
            database.writeEvidenceReport("g2-vchord-scale-default-plan-l2.json", Map.of(
                    "provider", "vchord_bm25",
                    "containerImageId", database.imageId(),
                    "candidateCount", SCALE_CANDIDATE_COUNT,
                    "bodyScopePlan", queryPlan,
                    "defaultPlannerOnly", true,
                    "enableSeqScan", plannerSettings[0],
                    "enableIndexScan", plannerSettings[1],
                    "measurementBoundary", "EXPLAIN ANALYZE 仅记录数据库侧单次执行计划；不与 G2-0 的 JDBC p95 作绝对比较。"
            ));
        } finally {
            database.sql("DROP TABLE IF EXISTS " + SCALE_TABLE + " CASCADE");
        }
    }

    private void createFixtureAndIndex() {
        database.sql("""
                CREATE TABLE bm25_catalog.g2_vchord_provider_poc (
                    id BIGINT PRIMARY KEY,
                    kb_id TEXT NOT NULL,
                    source_name TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    content_path TEXT NOT NULL,
                    title_token_vector bm25_catalog.bm25vector NOT NULL,
                    content_token_vector bm25_catalog.bm25vector NOT NULL
                )
                """);
        database.sql("""
                INSERT INTO bm25_catalog.g2_vchord_provider_poc VALUES
                    (1, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Title', '{10:1,20:1}'::bm25_catalog.bm25vector, '{80:1}'::bm25_catalog.bm25vector),
                    (2, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Body', '{90:1}'::bm25_catalog.bm25vector, '{30:1,40:1}'::bm25_catalog.bm25vector)
                """);
        database.sql("""
                INSERT INTO bm25_catalog.g2_vchord_provider_poc
                SELECT 100 + item, 'kb-outside', 'other.md', 'txt', 'Elsewhere > Distractor',
                       '{10:4,20:4}'::bm25_catalog.bm25vector, '{30:4,40:4}'::bm25_catalog.bm25vector
                FROM generate_series(1, 20) AS item
                """);
        createIndex();
    }

    private void createScaleFixtureAndIndex() {
        database.sql("""
                CREATE TABLE bm25_catalog.g2_vchord_scale_poc (
                    id BIGINT PRIMARY KEY,
                    kb_id TEXT NOT NULL,
                    source_name TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    content_path TEXT NOT NULL,
                    content_token_vector bm25_catalog.bm25vector NOT NULL
                )
                """);
        database.sql("""
                INSERT INTO bm25_catalog.g2_vchord_scale_poc
                VALUES (1, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Body', '{30:1,40:1}'::bm25_catalog.bm25vector)
                """);
        database.sql("""
                INSERT INTO bm25_catalog.g2_vchord_scale_poc
                SELECT 100 + item, 'kb-outside', 'other.md', 'txt', 'Elsewhere > Distractor',
                       '{30:4,40:4}'::bm25_catalog.bm25vector
                FROM generate_series(1, 20) AS item
                """);
        database.sql("""
                INSERT INTO bm25_catalog.g2_vchord_scale_poc
                SELECT 1000 + item, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Body',
                       '{50:1}'::bm25_catalog.bm25vector
                FROM generate_series(1, 4979) AS item
                """);
        database.sql("""
                CREATE INDEX g2_vchord_scale_poc_body_idx
                ON bm25_catalog.g2_vchord_scale_poc
                USING bm25 (content_token_vector bm25_catalog.bm25_ops)
                """);
        database.sql("ANALYZE " + SCALE_TABLE);
    }

    private FrozenFixture frozenFixture(RagEvaluationDataset dataset) throws Exception {
        List<FrozenChunk> chunks = new ArrayList<>();
        chunks.addAll(readFrozenChunks(
                "rag-eval/datasets/corpus/g2-pre-bm25-v1/g2-architecture.md",
                "g2-architecture",
                "g2-architecture.md",
                "md"
        ));
        chunks.addAll(readFrozenChunks(
                "rag-eval/datasets/corpus/g2-pre-bm25-v1/g2-architecture-pdf-pages.md",
                "architecture.pdf",
                "architecture.pdf",
                "pdf"
        ));
        Map<String, Integer> tokenIds = new LinkedHashMap<>();
        for (FrozenChunk chunk : chunks) {
            registerTokens(tokenIds, titleSearchText(chunk));
            registerTokens(tokenIds, chunk.content());
        }
        for (RagEvaluationCase evaluationCase : dataset.cases()) {
            registerTokens(tokenIds, evaluationCase.query());
        }
        List<FrozenVectorChunk> vectors = chunks.stream()
                .map(chunk -> new FrozenVectorChunk(
                        chunk.chunkId(),
                        chunk.sourceName(),
                        chunk.sourceType(),
                        chunk.contentPath(),
                        toBm25Vector(tokenIds, titleSearchText(chunk)),
                        toBm25Vector(tokenIds, chunk.content())
                ))
                .toList();
        return new FrozenFixture(Map.copyOf(tokenIds), vectors);
    }

    private List<FrozenChunk> readFrozenChunks(
            String resourcePath,
            String sourceDocumentId,
            String sourceName,
            String sourceType
    ) throws Exception {
        MarkdownParserService parser = new MarkdownParserServiceImpl();
        try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            return parser.parseMarkdown(inputStream).stream()
                    .map(section -> new FrozenChunk(
                            sourceDocumentId + "#" + section.getTitle() + "#0",
                            sourceName,
                            sourceType,
                            section.getContentPath(),
                            section.getTitle(),
                            section.getContent()
                    ))
                    .toList();
        }
    }

    private void createFrozenFixture(List<FrozenVectorChunk> chunks) {
        database.sql("""
                CREATE TABLE bm25_catalog.g2_vchord_frozen_poc (
                    chunk_id TEXT PRIMARY KEY,
                    kb_id TEXT NOT NULL,
                    source_name TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    content_path TEXT NOT NULL,
                    title_token_vector bm25_catalog.bm25vector NOT NULL,
                    content_token_vector bm25_catalog.bm25vector NOT NULL
                )
                """);
        for (FrozenVectorChunk chunk : chunks) {
            database.sql("""
                    INSERT INTO bm25_catalog.g2_vchord_frozen_poc
                    VALUES ('%s', 'g2-baseline-kb', '%s', '%s', '%s', '%s'::bm25_catalog.bm25vector, '%s'::bm25_catalog.bm25vector)
                    """.formatted(
                    sqlLiteral(chunk.chunkId()),
                    sqlLiteral(chunk.sourceName()),
                    sqlLiteral(chunk.sourceType()),
                    sqlLiteral(chunk.contentPath()),
                    chunk.titleVector(),
                    chunk.contentVector()
            ));
        }
        database.sql("""
                CREATE INDEX g2_vchord_frozen_poc_title_idx
                ON bm25_catalog.g2_vchord_frozen_poc
                USING bm25 (title_token_vector bm25_catalog.bm25_ops)
                """);
        database.sql("""
                CREATE INDEX g2_vchord_frozen_poc_body_idx
                ON bm25_catalog.g2_vchord_frozen_poc
                USING bm25 (content_token_vector bm25_catalog.bm25_ops)
                """);
    }

    private String frozenQuery(RagEvaluationCase evaluationCase, Map<String, Integer> tokenIds) {
        boolean titleQuery = "title_exact".equals(evaluationCase.queryType());
        String vectorColumn = titleQuery ? "title_token_vector" : "content_token_vector";
        String index = titleQuery ? FROZEN_TITLE_INDEX : FROZEN_BODY_INDEX;
        return "SET search_path = bm25_catalog, pg_catalog, public; " + """
                SELECT chunk_id FROM g2_vchord_frozen_poc
                WHERE kb_id = 'g2-baseline-kb'
                ORDER BY %s <&> to_bm25query('%s'::regclass, '%s'::bm25vector)
                LIMIT 1
                """.formatted(vectorColumn, index, toBm25Vector(tokenIds, evaluationCase.query()));
    }

    private void registerTokens(Map<String, Integer> tokenIds, String text) {
        for (String token : RetrievableTitleLexicalizer.tokenizeWithDuplicates(text)) {
            tokenIds.computeIfAbsent(token, ignored -> tokenIds.size() + 1);
        }
    }

    private String titleSearchText(FrozenChunk chunk) {
        return RetrievableTitleLexicalizer.buildSearchText(
                chunk.title(), chunk.title(), chunk.contentPath(), chunk.sourceName()
        );
    }

    private String toBm25Vector(Map<String, Integer> tokenIds, String text) {
        Map<Integer, Integer> frequencyByTokenId = new TreeMap<>();
        for (String token : RetrievableTitleLexicalizer.tokenizeWithDuplicates(text)) {
            Integer tokenId = tokenIds.get(token);
            if (tokenId != null) {
                frequencyByTokenId.merge(tokenId, 1, Integer::sum);
            }
        }
        return "{" + frequencyByTokenId.entrySet().stream()
                .map(item -> item.getKey() + ":" + item.getValue())
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private void createIndex() {
        database.sql("""
                CREATE INDEX g2_vchord_provider_poc_title_idx
                ON bm25_catalog.g2_vchord_provider_poc
                USING bm25 (title_token_vector bm25_catalog.bm25_ops)
                """);
        database.sql("""
                CREATE INDEX g2_vchord_provider_poc_body_idx
                ON bm25_catalog.g2_vchord_provider_poc
                USING bm25 (content_token_vector bm25_catalog.bm25_ops)
                """);
    }

    private String scopedTitleQuery() {
        return "SET search_path = bm25_catalog, pg_catalog, public; " + """
                SELECT id FROM g2_vchord_provider_poc
                WHERE kb_id = 'kb-authorized'
                  AND source_name = 'architecture.md'
                  AND source_type = 'md'
                  AND content_path = 'RAG > BM25 > Title'
                ORDER BY title_token_vector <&> to_bm25query('g2_vchord_provider_poc_title_idx'::regclass, '{10:1,20:1}'::bm25vector)
                LIMIT 1
                """;
    }

    private String scopedBodyQuery() {
        return scopedBodyQuery("RAG > BM25 > Body");
    }

    private String scopedBodyQuery(String normalizedContentPath) {
        return "SET search_path = bm25_catalog, pg_catalog, public; " + scopedBodySelect(normalizedContentPath);
    }

    private String scopedBodySelect() {
        return scopedBodySelect("RAG > BM25 > Body");
    }

    private String scopedBodySelect(String normalizedContentPath) {
        return """
                SELECT id FROM g2_vchord_provider_poc
                WHERE kb_id = 'kb-authorized'
                  AND source_name = 'architecture.md'
                  AND source_type = 'md'
                  AND content_path = '%s'
                ORDER BY content_token_vector <&> to_bm25query('g2_vchord_provider_poc_body_idx'::regclass, '{30:1,40:1}'::bm25vector)
                LIMIT 1
                """.formatted(sqlLiteral(normalizedContentPath));
    }

    private String scaleScopedBodyQuery() {
        return "SET search_path = bm25_catalog, pg_catalog, public; " + scaleScopedBodySelect();
    }

    private String scaleScopedBodySelect() {
        return """
                SELECT id FROM g2_vchord_scale_poc
                WHERE kb_id = 'kb-authorized'
                  AND source_name = 'architecture.md'
                  AND source_type = 'md'
                  AND content_path = 'RAG > BM25 > Body'
                ORDER BY content_token_vector <&> to_bm25query('g2_vchord_scale_poc_body_idx'::regclass, '{30:1,40:1}'::bm25vector)
                LIMIT 1
                """;
    }

    private String scaleUnscopedBodyRankingQuery() {
        return "SET search_path = bm25_catalog, pg_catalog, public; " + """
                SELECT id || '|' || (content_token_vector <&> to_bm25query('g2_vchord_scale_poc_body_idx'::regclass, '{30:1,40:1}'::bm25vector))
                FROM g2_vchord_scale_poc
                ORDER BY content_token_vector <&> to_bm25query('g2_vchord_scale_poc_body_idx'::regclass, '{30:1,40:1}'::bm25vector)
                LIMIT 25
                """;
    }

    private String unscopedBodyQuery() {
        return "SET search_path = bm25_catalog, pg_catalog, public; " + """
                SELECT id FROM g2_vchord_provider_poc
                ORDER BY content_token_vector <&> to_bm25query('g2_vchord_provider_poc_body_idx'::regclass, '{30:1,40:1}'::bm25vector)
                LIMIT 1
                """;
    }

    private String unscopedBodyRankingQuery() {
        return "SET search_path = bm25_catalog, pg_catalog, public; " + """
                SELECT id || '|' || (content_token_vector <&> to_bm25query('g2_vchord_provider_poc_body_idx'::regclass, '{30:1,40:1}'::bm25vector))
                FROM g2_vchord_provider_poc
                ORDER BY content_token_vector <&> to_bm25query('g2_vchord_provider_poc_body_idx'::regclass, '{30:1,40:1}'::bm25vector)
                """;
    }

    private int candidateId(String serializedCandidate) {
        return Integer.parseInt(serializedCandidate.substring(0, serializedCandidate.indexOf('|')));
    }

    private double candidateScore(String serializedCandidate) {
        return Double.parseDouble(serializedCandidate.substring(serializedCandidate.indexOf('|') + 1));
    }

    private double candidateScore(String serializedRanking, int candidateId) {
        return serializedRanking.lines()
                .filter(item -> candidateId(item) == candidateId)
                .mapToDouble(this::candidateScore)
                .findFirst()
                .orElseThrow();
    }

    private record FrozenFixture(Map<String, Integer> tokenIds, List<FrozenVectorChunk> chunks) {
    }

    private record FrozenChunk(
            String chunkId,
            String sourceName,
            String sourceType,
            String contentPath,
            String title,
            String content
    ) {
    }

    private record FrozenVectorChunk(
            String chunkId,
            String sourceName,
            String sourceType,
            String contentPath,
            String titleVector,
            String contentVector
    ) {
    }
}
