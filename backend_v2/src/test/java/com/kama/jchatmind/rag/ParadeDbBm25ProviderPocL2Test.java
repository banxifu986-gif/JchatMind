package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "g2.native-bm25.poc.l2", matches = "true")
class ParadeDbBm25ProviderPocL2Test {

    private static final String TABLE = "public.g2_paradedb_provider_poc";
    private static final String INDEX = "public.g2_paradedb_provider_poc_idx";
    private static final String FROZEN_TABLE = "public.g2_paradedb_frozen_poc";
    private final IsolatedPostgresContainer database = new IsolatedPostgresContainer(
            "g2-paradedb-poc", "g2paradedb", "sha256:82d0c8bb0263c4320cb321591dd6831ecdd04b4b27328ef658358a9a8c383ac5"
    );

    @Test
    void keepsAllHardScopeFiltersAheadOfScopedTitleAndBodyTopNAfterBackupRestore() {
        database.assertIsolation();
        assertTrue(database.sql("SHOW server_version").trim().startsWith("18.6"));
        assertEquals("0.25.3", database.sql("SELECT extversion FROM pg_extension WHERE extname = 'pg_search'").trim());
        assertEquals("0.8.4", database.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'").trim());
        database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        try {
            createFixtureAndIndex();

            assertEquals("1", database.sql(scopedTitleQuery()).trim());
            String normalizedBodyPath = IsolatedPostgresContainer.normalizeContentPath(" RAG> BM25  >Body ");
            assertEquals("RAG > BM25 > Body", normalizedBodyPath);
            assertEquals("2", database.sql(scopedBodyQuery(normalizedBodyPath)).trim());
            String bodyPlan = database.sql("EXPLAIN (COSTS OFF) " + scopedBodyQuery(normalizedBodyPath));
            assertTrue(bodyPlan.contains("ParadeDB Base Scan"));
            assertTrue(bodyPlan.contains("kb_id = 'kb-authorized'"));
            assertTrue(bodyPlan.contains("source_name = 'architecture.md'"));
            assertTrue(bodyPlan.contains("source_type = 'md'"));
            assertTrue(bodyPlan.contains("content_path = 'RAG > BM25 > Body'"));

            database.sql("DELETE FROM " + TABLE + " WHERE id = 2");
            assertEquals("", database.sql(scopedBodyQuery(normalizedBodyPath)).trim(), "删除后不得出现 stale hit");
            database.sql("INSERT INTO " + TABLE + " VALUES (2, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Body', 'ordinary title', 'body lexical filter before top n')");
            database.sql("DROP INDEX " + INDEX);
            createIndex();
            assertEquals("2", database.sql(scopedBodyQuery(normalizedBodyPath)).trim(), "重建后正文通道应恢复同一 scope 内 gold");

            String backup = database.dumpTable(TABLE);
            assertTrue(backup.contains("CREATE INDEX g2_paradedb_provider_poc_idx"));
            database.sql("DROP TABLE " + TABLE);
            assertEquals("", database.sql("SELECT to_regclass('" + TABLE + "')").trim());
            database.restore(backup);

            assertEquals("1", database.sql(scopedTitleQuery()).trim());
            assertEquals("2", database.sql(scopedBodyQuery(normalizedBodyPath)).trim());
            assertTrue(database.sql("EXPLAIN (COSTS OFF) " + scopedBodyQuery(normalizedBodyPath)).contains("ParadeDB Base Scan"));
            assertTrue(database.sql("SELECT pg_get_indexdef('" + INDEX + "'::regclass)").contains("USING bm25"));
            database.writeEvidenceReport("g2-paradedb-provider-poc-l2.json", Map.of(
                    "provider", "pg_search",
                    "containerImageId", database.imageId(),
                    "databaseVersion", database.sql("SHOW server_version").trim(),
                    "extensionVersion", database.sql("SELECT extversion FROM pg_extension WHERE extname = 'pg_search'").trim(),
                    "vectorExtensionVersion", database.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'").trim(),
                    "bodyScopePlan", bodyPlan,
                    "backupRestoreIndexDefinition", database.sql("SELECT pg_get_indexdef('" + INDEX + "'::regclass)").trim(),
                    "defaultPlannerOnly", true
            ));
        } finally {
            database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        }
    }

    @Test
    void demonstratesThatGlobalBodyTopNDoesNotRepresentTheAuthorizedScope() {
        database.assertIsolation();
        database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        try {
            createFixtureAndIndex();

            String unscopedRanking = database.sql(unscopedBodyRankingQuery()).trim();
            String outsideTopN = unscopedRanking.lines().findFirst().orElseThrow();
            assertTrue(candidateId(outsideTopN) > 100, "全局 Top-N 必须实际返回范围外干扰项");
            assertTrue(candidateScore(outsideTopN) > candidateScore(unscopedRanking, 2), "范围外干扰项必须拥有更高 native BM25 分数");
        } finally {
            database.sql("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
        }
    }

    @Test
    void demonstratesThatDefaultAnalyzerMissesFrozenChineseTechnicalTerm() throws Exception {
        database.assertIsolation();
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json"
        );
        assertEquals("g2-pre-bm25-v1", dataset.manifest().datasetId());
        assertEquals(9, dataset.cases().size());
        database.sql("DROP TABLE IF EXISTS " + FROZEN_TABLE + " CASCADE");
        try {
            createFrozenFixture();
            RagEvaluationCase evaluationCase = dataset.cases().stream()
                    .filter(item -> "g2-pre-bm25-v1-001".equals(item.caseId()))
                    .findFirst()
                    .orElseThrow();
            String actualTopChunkId = database.sql(frozenQuery(evaluationCase)).trim();
            assertEquals(
                    "g2-architecture#JVM 词法候选边界#0",
                    actualTopChunkId,
                    "ParadeDB 默认 analyzer 的中文技术术语误召回应保持可复查"
            );
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("provider", "pg_search");
            report.put("containerImageId", database.imageId());
            report.put("datasetId", dataset.manifest().datasetId());
            report.put("caseId", evaluationCase.caseId());
            report.put("goldChunkId", evaluationCase.goldChunkIds().get(0));
            report.put("actualTopChunkId", actualTopChunkId);
            report.put("result", "default_analyzer_miss");
            database.writeEvidenceReport("g2-paradedb-frozen-analyzer-l2.json", report);
        } finally {
            database.sql("DROP TABLE IF EXISTS " + FROZEN_TABLE + " CASCADE");
        }
    }

    private void createFixtureAndIndex() {
        database.sql("""
                CREATE TABLE public.g2_paradedb_provider_poc (
                    id BIGINT PRIMARY KEY,
                    kb_id TEXT NOT NULL,
                    source_name TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    content_path TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL
                )
                """);
        database.sql("""
                INSERT INTO public.g2_paradedb_provider_poc VALUES
                    (1, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Title', 'scoped title retrieval contract', 'ordinary body'),
                    (2, 'kb-authorized', 'architecture.md', 'md', 'RAG > BM25 > Body', 'ordinary title', 'body lexical filter before top n')
                """);
        database.sql("""
                INSERT INTO public.g2_paradedb_provider_poc
                SELECT 100 + item, 'kb-outside', 'other.md', 'txt', 'Elsewhere > Distractor',
                       'scoped title retrieval contract scoped title retrieval contract',
                       'body lexical filter before top n body lexical filter before top n'
                FROM generate_series(1, 20) AS item
                """);
        createIndex();
    }

    private void createFrozenFixture() throws Exception {
        database.sql("""
                CREATE TABLE public.g2_paradedb_frozen_poc (
                    chunk_id TEXT PRIMARY KEY,
                    kb_id TEXT NOT NULL,
                    source_name TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    content_path TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL
                )
                """);
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
        for (FrozenChunk chunk : chunks) {
            database.sql("""
                    INSERT INTO public.g2_paradedb_frozen_poc
                    VALUES ('%s', 'g2-baseline-kb', '%s', '%s', '%s', '%s', '%s')
                    """.formatted(
                    sqlLiteral(chunk.chunkId()),
                    sqlLiteral(chunk.sourceName()),
                    sqlLiteral(chunk.sourceType()),
                    sqlLiteral(chunk.contentPath()),
                    sqlLiteral(chunk.title()),
                    sqlLiteral(chunk.content())
            ));
        }
        database.sql("""
                CREATE INDEX g2_paradedb_frozen_poc_idx
                ON public.g2_paradedb_frozen_poc
                USING bm25 (chunk_id, title, content, kb_id, source_name, source_type, content_path)
                WITH (key_field = chunk_id)
                """);
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

    private String frozenQuery(RagEvaluationCase evaluationCase) {
        String field = "title_exact".equals(evaluationCase.queryType()) ? "title" : "content";
        return """
                SELECT chunk_id FROM public.g2_paradedb_frozen_poc
                WHERE %s @@@ '%s'
                  AND kb_id = 'g2-baseline-kb'
                ORDER BY paradedb.score(chunk_id) DESC
                LIMIT 1
                """.formatted(field, sqlLiteral(evaluationCase.query()));
    }

    private String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private void createIndex() {
        database.sql("""
                CREATE INDEX g2_paradedb_provider_poc_idx
                ON public.g2_paradedb_provider_poc
                USING bm25 (id, title, content, kb_id, source_name, source_type, content_path)
                WITH (key_field = id)
                """);
    }

    private String scopedTitleQuery() {
        return """
                SELECT id FROM public.g2_paradedb_provider_poc
                WHERE title @@@ 'scoped title retrieval contract'
                  AND kb_id = 'kb-authorized'
                  AND source_name = 'architecture.md'
                  AND source_type = 'md'
                  AND content_path = 'RAG > BM25 > Title'
                ORDER BY paradedb.score(id) DESC
                LIMIT 1
                """;
    }

    private String scopedBodyQuery() {
        return scopedBodyQuery("RAG > BM25 > Body");
    }

    private String scopedBodyQuery(String normalizedContentPath) {
        return """
                SELECT id FROM public.g2_paradedb_provider_poc
                WHERE content @@@ 'body lexical filter before top n'
                  AND kb_id = 'kb-authorized'
                  AND source_name = 'architecture.md'
                  AND source_type = 'md'
                  AND content_path = '%s'
                ORDER BY paradedb.score(id) DESC
                LIMIT 1
                """.formatted(sqlLiteral(normalizedContentPath));
    }

    private String unscopedBodyQuery() {
        return """
                SELECT id FROM public.g2_paradedb_provider_poc
                WHERE content @@@ 'body lexical filter before top n'
                ORDER BY paradedb.score(id) DESC
                LIMIT 1
                """;
    }

    private String unscopedBodyRankingQuery() {
        return """
                SELECT id || '|' || paradedb.score(id) FROM public.g2_paradedb_provider_poc
                WHERE content @@@ 'body lexical filter before top n'
                ORDER BY paradedb.score(id) DESC
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

    private record FrozenChunk(
            String chunkId,
            String sourceName,
            String sourceType,
            String contentPath,
            String title,
            String content
    ) {
    }
}
