package com.kama.jchatmind.rag;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.impl.BgeRerankerService;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.service.impl.VchordBm25QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@EnabledIfSystemProperty(named = "rag.eval.mmarco.enabled", matches = "true")
@SpringBootTest(
        classes = MmarcoZhSampledRuntimeEvaluationTest.MmarcoZhSampledRuntimeTestConfig.class,
        properties = {
                "spring.ai.mcp.client.enabled=false",
                "rag.query-rewrite.llm.enabled=false",
                MmarcoZhSampledRuntimeEvaluationTest.MMARCO_RERANK_TIMEOUT_PROPERTY,
                MmarcoZhSampledRuntimeEvaluationTest.IVFFLAT_CONNECTION_INIT_SQL
        }
)
@ActiveProfiles("rag-eval")
class MmarcoZhSampledRuntimeEvaluationTest {

    private static final String DATASET_VERSION = "mmarco-zh-sampled-v3-local-diagnostic";
    private static final String OWNER_ID = "900000000003";
    private static final int TOTAL_QUERY_COUNT = 300;
    private static final int DEVELOPMENT_QUERY_COUNT = 200;
    private static final int HARD_NEGATIVES_PER_QUERY = 1;
    private static final int RANDOM_DISTRACTOR_COUNT = 500;
    private static final int EXPECTED_CANDIDATE_COUNT = 1_116;
    private static final long RANDOM_SEED = 20_260_825L;
    private static final int TOP_K = 10;
    private static final int RERANK_CANDIDATE_COUNT = 50;
    private static final int EMBEDDING_BATCH_SIZE = 8;
    static final int IVFFLAT_PROBES = 100;
    static final int MMARCO_RERANK_TIMEOUT_MS = 300_000;
    static final String MMARCO_RERANK_TIMEOUT_PROPERTY = "rag.rerank.timeout-ms=" + MMARCO_RERANK_TIMEOUT_MS;
    static final String RERANK_BATCHING_CONFIG = "tei-client-batch-size=32\ntei-client-max-concurrent-batches=1";
    private static final String RERANK_RUN_CONFIGURATION = "tei-serial-batches-v1";
    static final String IVFFLAT_CONNECTION_INIT_SQL = "spring.datasource.hikari.connection-init-sql="
            + "SELECT set_config('ivfflat.probes', '" + IVFFLAT_PROBES
            + "', false) WHERE vector_dims('[0]'::vector) = 1";
    private static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final Path DATASET_DIRECTORY = Path.of("target", "rag-eval", "external", DATASET_VERSION);
    private static final Path EVALUATION_DIRECTORY = DATASET_DIRECTORY.resolve(RERANK_RUN_CONFIGURATION);
    private static final Path MANIFEST_PATH = DATASET_DIRECTORY.resolve(DATASET_VERSION + "-manifest.json");
    private static final Path INPUT_DIRECTORY = Path.of(
            "target", "rag-eval", "external", "mmarco-zh-sampled-v1", "input"
    );

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private VchordBm25QueryService vchordBm25QueryService;

    @Autowired
    private VchordBm25ProjectionService vchordBm25ProjectionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model:bge-m3:latest}")
    private String embeddingModel;

    @Value("${rag.rerank.base-url:http://127.0.0.1:8081}")
    private String rerankerBaseUrl;

    @Value("${rag.rerank.timeout-ms:3000}")
    private int rerankerTimeoutMs;

    @Value("${rag.eval.mmarco.split:development}")
    private String evaluationSplit;

    @Value("${rag.eval.mmarco.max-queries:0}")
    private int maxQueryCount;

    @Test
    void freezesImportsAndEvaluatesMmarcoZhWithComparableRerankArms() throws Exception {
        assertIsolatedDatabase();
        Path manifestPath = loadFrozenManifest();
        MmarcoZhSampledManifestImporter.ManifestImportResult imported = importFrozenCandidates(manifestPath);
        List<MmarcoZhSampledDatasetFreezer.Query> evaluationQueries = evaluationQueries(imported.manifest());
        MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint = fingerprint(imported, evaluationQueries);
        BgeRerankerService teiReranker = new BgeRerankerService(
                WebClient.builder(), true, rerankerBaseUrl, rerankerTimeoutMs
        );

        RagServiceImpl rrfOnlyService = ragService(Arm.RRF_ONLY, teiReranker);
        verifyTeiHealth(evaluationQueries.get(0), imported.importResult().knowledgeBaseId(), rrfOnlyService, teiReranker);

        Map<Arm, Map<Integer, Path>> runs = executeArms(
                evaluationQueries,
                imported,
                fingerprint,
                teiReranker
        );
        writeReport(runs, imported.candidateManifestSha256());
    }

    private void assertIsolatedDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!ISOLATED_JDBC_URL.equals(connection.getMetaData().getURL())
                    || !"jchatmind_rag_eval".equals(connection.getCatalog())) {
                throw new IllegalStateException("mMARCO 评测只能连接 127.0.0.1:55432/jchatmind_rag_eval");
            }
        }
    }

    private Path loadFrozenManifest() throws IOException {
        if (!Files.isRegularFile(MANIFEST_PATH) || !Files.isReadable(MANIFEST_PATH)) {
            throw new IllegalStateException("mMARCO frozen manifest 缺失，必须先执行冻结验证");
        }
        MmarcoZhSampledDatasetFreezer.FrozenManifest manifest = objectMapper.readValue(
                Files.readString(MANIFEST_PATH), MmarcoZhSampledDatasetFreezer.FrozenManifest.class
        );
        assertFrozenDataset(manifest);
        MmarcoZhSampledDatasetFreezer.SourceSha256 expectedSourceSha256 = manifest.sourceSha256();
        MmarcoZhSampledDatasetFreezer.SourceSha256 actualSourceSha256 = new MmarcoZhSampledDatasetFreezer.SourceSha256(
                sha256(INPUT_DIRECTORY.resolve("collection.tsv")),
                sha256(INPUT_DIRECTORY.resolve("queries.tsv")),
                sha256(INPUT_DIRECTORY.resolve("qrels.tsv")),
                sha256(INPUT_DIRECTORY.resolve("run.bm25.tsv"))
        );
        if (!expectedSourceSha256.equals(actualSourceSha256)) {
            throw new IllegalStateException("mMARCO frozen manifest source SHA-256 不匹配");
        }
        return MANIFEST_PATH;
    }

    private void assertFrozenDataset(MmarcoZhSampledDatasetFreezer.FrozenManifest manifest) {
        MmarcoZhSampledDatasetFreezer.FreezeRequest request = manifest.freezeRequest();
        if (!DATASET_VERSION.equals(manifest.datasetVersion())
                || !MmarcoZhSampledDatasetFreezer.UPSTREAM_REVISION.equals(manifest.upstreamRevision())
                || !MmarcoZhSampledDatasetFreezer.LANGUAGE.equals(manifest.language())
                || !MmarcoZhSampledDatasetFreezer.MAPPING_VERSION.equals(manifest.mappingVersion())
                || request == null
                || request.totalQueryCount() != TOTAL_QUERY_COUNT
                || request.developmentQueryCount() != DEVELOPMENT_QUERY_COUNT
                || request.hardNegativesPerQuery() != HARD_NEGATIVES_PER_QUERY
                || request.randomDistractorCount() != RANDOM_DISTRACTOR_COUNT
                || request.randomSeed() != RANDOM_SEED
                || manifest.developmentQueries().size() != DEVELOPMENT_QUERY_COUNT
                || manifest.untouchedTestQueries().size() != TOTAL_QUERY_COUNT - DEVELOPMENT_QUERY_COUNT
                || manifest.candidates().size() != EXPECTED_CANDIDATE_COUNT) {
            throw new IllegalStateException("mMARCO frozen dataset 未满足固定样本或候选规模");
        }
        Set<String> developmentIds = manifest.developmentQueries().stream()
                .map(MmarcoZhSampledDatasetFreezer.Query::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> testIds = manifest.untouchedTestQueries().stream()
                .map(MmarcoZhSampledDatasetFreezer.Query::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        developmentIds.retainAll(testIds);
        if (!developmentIds.isEmpty()) {
            throw new IllegalStateException("mMARCO development 与 untouched test query 重叠");
        }
    }

    private MmarcoZhSampledManifestImporter.ManifestImportResult importFrozenCandidates(Path manifestPath) throws Exception {
        MmarcoZhSampledOllamaBatchEmbedder embeddingService = new MmarcoZhSampledOllamaBatchEmbedder(
                WebClient.builder(), ollamaBaseUrl, embeddingModel, EMBEDDING_BATCH_SIZE
        );
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MmarcoZhSampledManifestImporter.ManifestImportResult imported = new MmarcoZhSampledManifestImporter()
                        .importManifest(
                                manifestPath,
                                connection,
                                OWNER_ID,
                                EMBEDDING_BATCH_SIZE,
                                embeddingService::embedAll,
                                this::projectBm25
                        );
                connection.commit();
                return imported;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private MmarcoZhSampledManifestImporter.Bm25Projection projectBm25(String passageId, String content) {
        String title = passageTitle(passageId);
        VchordBm25ProjectionService.Projection projection = vchordBm25ProjectionService.project(
                RetrievableTitleLexicalizer.buildSearchText(
                        title,
                        title,
                        "mMARCO > zh > " + passageId,
                        "mMARCO zh sampled"
                ),
                content
        );
        return new MmarcoZhSampledManifestImporter.Bm25Projection(
                projection.titleVector(), projection.contentVector(), projection.indexVersion()
        );
    }

    private List<MmarcoZhSampledDatasetFreezer.Query> evaluationQueries(
            MmarcoZhSampledDatasetFreezer.FrozenManifest manifest
    ) {
        List<MmarcoZhSampledDatasetFreezer.Query> splitQueries;
        if ("development".equals(evaluationSplit)) {
            splitQueries = manifest.developmentQueries();
        } else if ("untouched-test".equals(evaluationSplit)) {
            splitQueries = manifest.untouchedTestQueries();
        } else {
            throw new IllegalArgumentException("rag.eval.mmarco.split 只能为 development 或 untouched-test");
        }
        if (maxQueryCount <= 0 || maxQueryCount >= splitQueries.size()) {
            return splitQueries;
        }
        return List.copyOf(splitQueries.subList(0, maxQueryCount));
    }

    private MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint(
            MmarcoZhSampledManifestImporter.ManifestImportResult imported,
            List<MmarcoZhSampledDatasetFreezer.Query> evaluationQueries
    ) {
        MmarcoZhSampledDatasetFreezer.SourceSha256 source = imported.manifest().sourceSha256();
        return new MmarcoZhSampledEvaluator.EvaluationFingerprint(
                imported.manifest().datasetVersion(),
                sha256(source.collection() + "\n" + source.queries() + "\n" + source.qrels() + "\n" + source.hardNegativeRun()),
                imported.candidateManifestSha256(),
                imported.manifest().mappingVersion(),
                "vchord-bm25-0.3.0/index-" + VchordBm25ProjectionService.INDEX_VERSION,
                embeddingModel,
                "rag_bm25_token_dictionary/v1",
                sha256(rerankConfiguration(rerankerTimeoutMs)),
                TOP_K,
                RERANK_CANDIDATE_COUNT,
                sha256(evaluationQueries.stream().map(query -> query.id() + "\t" + query.text()).toList())
        );
    }

    static String rerankConfiguration(int rerankerTimeoutMs) {
        return "rrf-k=60\ntop-k=10\ncandidate-budget=50\nquery-expansion=false\nrerank-timeout-ms="
                + rerankerTimeoutMs + "\nembedding-import-batch-size=" + EMBEDDING_BATCH_SIZE
                + "\nembedding-import-timeout-ms=" + MmarcoZhSampledOllamaBatchEmbedder.EMBEDDING_TIMEOUT_MILLIS
                + "\nembedding-import-max-response-bytes=" + MmarcoZhSampledOllamaBatchEmbedder.MAX_RESPONSE_BYTES
                + "\nivfflat-probes=" + IVFFLAT_PROBES + "\n" + RERANK_BATCHING_CONFIG;
    }

    private void verifyTeiHealth(
            MmarcoZhSampledDatasetFreezer.Query query,
            String knowledgeBaseId,
            RagServiceImpl rrfOnlyService,
            BgeRerankerService teiReranker
    ) {
        List<RagRetrievalResult> candidates = rrfOnlyService.retrieve(
                List.of(knowledgeBaseId), query.text(), RERANK_CANDIDATE_COUNT
        );
        if (candidates.size() != RERANK_CANDIDATE_COUNT) {
            throw new IllegalStateException("TEI 健康检查未获得 50 个 RRF 候选");
        }
        List<Double> scores = teiReranker.rerank(query.text(), candidates.stream().map(this::rerankText).toList());
        if (scores.size() != RERANK_CANDIDATE_COUNT || scores.stream().anyMatch(score -> score == null || !Double.isFinite(score))) {
            throw new IllegalStateException("TEI 健康检查未返回 50 个完整有效分数");
        }
    }

    private Map<Arm, Map<Integer, Path>> executeArms(
            List<MmarcoZhSampledDatasetFreezer.Query> queries,
            MmarcoZhSampledManifestImporter.ManifestImportResult imported,
            MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint,
            BgeRerankerService teiReranker
    ) throws IOException {
        List<ArmAttempt> schedule = new ArrayList<>();
        for (Arm arm : Arm.values()) {
            schedule.add(new ArmAttempt(arm, 1));
            schedule.add(new ArmAttempt(arm, 2));
        }
        java.util.Collections.shuffle(schedule, new Random(RANDOM_SEED));
        Map<Arm, Map<Integer, Path>> outputPaths = new EnumMap<>(Arm.class);
        for (ArmAttempt attempt : schedule) {
            MmarcoZhSampledEvaluator.VariantRun run = executeArm(
                    attempt.arm(), queries, imported, fingerprint, teiReranker
            );
            Path path = EVALUATION_DIRECTORY.resolve(attempt.arm().filePrefix() + "-attempt-" + attempt.number() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), run);
            outputPaths.computeIfAbsent(attempt.arm(), ignored -> new LinkedHashMap<>()).put(attempt.number(), path);
        }
        if (outputPaths.values().stream().anyMatch(paths -> paths.size() != 2 || !paths.containsKey(1) || !paths.containsKey(2))) {
            throw new IllegalStateException("mMARCO A/B/C 未各自完成两次独立运行");
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                EVALUATION_DIRECTORY.resolve(DATASET_VERSION + "-execution-order.json").toFile(),
                schedule.stream().map(attempt -> attempt.arm().variant() + "#" + attempt.number()).toList()
        );
        return outputPaths;
    }

    private MmarcoZhSampledEvaluator.VariantRun executeArm(
            Arm arm,
            List<MmarcoZhSampledDatasetFreezer.Query> queries,
            MmarcoZhSampledManifestImporter.ManifestImportResult imported,
            MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint,
            BgeRerankerService teiReranker
    ) {
        RagServiceImpl ragService = ragService(arm, teiReranker);
        MmarcoZhSampledRuntimeReplayRunner replayRunner = new MmarcoZhSampledRuntimeReplayRunner();
        Logger ragLogger = (Logger) LoggerFactory.getLogger(RagServiceImpl.class);
        ListAppender<ILoggingEvent> fallbackLog = new ListAppender<>();
        if (arm.usesTei()) {
            fallbackLog.start();
            ragLogger.addAppender(fallbackLog);
        }
        try {
            List<MmarcoZhSampledReplayCollector.RuntimeQueryResult> runtimeResults = replayRunner.run(
                    queries,
                    query -> retrieve(arm, ragService, fallbackLog, query, imported.importResult().knowledgeBaseId())
            );
            return new MmarcoZhSampledReplayCollector().collect(
                    arm.variant(),
                    fingerprint,
                    queries,
                    imported.manifest().goldLogicalChunkIdsByQueryId(),
                    imported.importResult().logicalChunkIdByRuntimeUuid(),
                    runtimeResults
            );
        } finally {
            if (arm.usesTei()) {
                ragLogger.detachAppender(fallbackLog);
                fallbackLog.stop();
            }
        }
    }

    private MmarcoZhSampledRuntimeReplayRunner.RetrievalOutcome retrieve(
            Arm arm,
            RagServiceImpl ragService,
            ListAppender<ILoggingEvent> fallbackLog,
            MmarcoZhSampledDatasetFreezer.Query query,
            String knowledgeBaseId
    ) {
        int logSizeBeforeRetrieve = fallbackLog.list.size();
        List<RagRetrievalResult> results = ragService.retrieve(List.of(knowledgeBaseId), query.text(), TOP_K);
        boolean teiFallback = arm.usesTei() && fallbackLog.list.stream()
                .skip(logSizeBeforeRetrieve)
                .anyMatch(event -> event.getFormattedMessage().startsWith("BGE reranker unavailable; fallback to local rerank:"));
        return new MmarcoZhSampledRuntimeReplayRunner.RetrievalOutcome(
                results.stream().map(RagRetrievalResult::getChunkId).toList(),
                teiFallback
        );
    }

    private RagServiceImpl ragService(Arm arm, BgeRerankerService teiReranker) {
        BgeRerankerService reranker = arm.usesTei()
                ? teiReranker
                : new BgeRerankerService(WebClient.builder(), false, rerankerBaseUrl, rerankerTimeoutMs);
        return new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                queryRewriteService,
                vchordBm25QueryService,
                reranker,
                ollamaBaseUrl,
                embeddingModel,
                false,
                true,
                arm.disableRerank(),
                2_048
        );
    }

    private void writeReport(Map<Arm, Map<Integer, Path>> paths, String candidateManifestSha256) throws IOException {
        Path reportPath = EVALUATION_DIRECTORY.resolve(DATASET_VERSION + "-retrieval-ab.json");
        new MmarcoZhSampledEvaluationRunner().evaluateAndWrite(
                paths.get(Arm.RRF_ONLY).get(2),
                paths.get(Arm.LOCAL_RULE_RERANK).get(2),
                paths.get(Arm.TEI_BGE_RERANK).get(2),
                reportPath,
                DATASET_VERSION + "-" + candidateManifestSha256.substring(0, 12) + "-" + evaluationSplit,
                "BAAI/bge-reranker-v2-m3"
        );
    }

    private String rerankText(RagRetrievalResult result) {
        String title = "";
        try {
            title = objectMapper.readTree(result.getMetadata()).path("retrievableTitle").asText();
            if (title.isBlank()) {
                title = objectMapper.readTree(result.getMetadata()).path("title").asText();
            }
        } catch (Exception ignored) {
            title = "";
        }
        return title.isBlank() ? result.getContent() : title + "\n" + result.getContent();
    }

    private String passageTitle(String passageId) {
        return "mMARCO zh passage " + passageId;
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(List<String> values) {
        return sha256(String.join("\n", values));
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private enum Arm {
        RRF_ONLY("rrf-only", "rrf-only", true, false),
        LOCAL_RULE_RERANK("local-rule-rerank", "local-rule-rerank", false, false),
        TEI_BGE_RERANK("tei-bge-rerank", "tei-bge-rerank", false, true);

        private final String variant;
        private final String filePrefix;
        private final boolean disableRerank;
        private final boolean usesTei;

        Arm(String variant, String filePrefix, boolean disableRerank, boolean usesTei) {
            this.variant = variant;
            this.filePrefix = filePrefix;
            this.disableRerank = disableRerank;
            this.usesTei = usesTei;
        }

        String variant() {
            return variant;
        }

        String filePrefix() {
            return filePrefix;
        }

        boolean disableRerank() {
            return disableRerank;
        }

        boolean usesTei() {
            return usesTei;
        }
    }

    private record ArmAttempt(Arm arm, int number) {
    }

    @Configuration
    @ImportAutoConfiguration({
            JacksonAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @MapperScan("com.kama.jchatmind.mapper")
    @Import({
            QueryRewriteServiceImpl.class,
            VchordBm25QueryService.class,
            VchordBm25ProjectionService.class
    })
    static class MmarcoZhSampledRuntimeTestConfig {

    }
}
