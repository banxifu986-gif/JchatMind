package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.impl.BgeRerankerService;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.service.impl.VchordBm25QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "g2.rag.three-branch.enabled", matches = "true")
@SpringBootTest(
        classes = RagIndependentBranchRuntimeEvaluationTest.RuntimeTestConfig.class,
        properties = {
                "spring.ai.mcp.client.enabled=false",
                "rag.query-rewrite.llm.enabled=false"
        }
)
@ActiveProfiles("rag-eval")
class RagIndependentBranchRuntimeEvaluationTest {

    private static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String OWNER_ID = "900000000004";
    private static final int TOP_K = 10;
    private static final int CANDIDATE_BUDGET = 50;
    private static final Path REPORT_PATH = Path.of(
            "target", "rag-eval", "three-branch", "g2-pre-bm25-v1-runtime.json"
    );

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

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

    @Test
    void replaysFrozenG2CasesAgainstRealIsolatedR0R1R2Candidates() throws Exception {
        assertIsolatedDatabase();
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json"
        );
        RagIndependentBranchReplayLoader.FrozenReplay replay = RagIndependentBranchReplayLoader.load(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json",
                "rag-eval/datasets/replays/g2-pre-bm25-v1-branches.jsonl"
        );
        RagIndependentBranchRuntimeFixture.Fixture fixture = RagIndependentBranchRuntimeFixture.load(dataset);
        RagIndependentBranchRuntimeImporter.ImportResult imported = importFixture(fixture);

        List<RagIndependentBranchEvaluator.VariantRun> runs = List.of(
                runVariant("R0", "current-flat", dataset, replay, fixture, imported),
                runVariant("R1", "two-branch-original", dataset, replay, fixture, imported),
                runVariant("R2", "three-branch-expanded", dataset, replay, fixture, imported)
        );
        writeReport(replay, fixture, imported, runs);

        assertThat(runs).allSatisfy(run -> assertThat(run.queryReplays()).hasSize(9));
        assertThat(queryReplay(runs.get(1), "g2-pre-bm25-v1-005").branchDiagnostics())
                .extracting(RagIndependentBranchEvaluator.BranchDiagnostic::branch)
                .containsExactly("dense-original", "sparse-original");
        assertThat(queryReplay(runs.get(2), "g2-pre-bm25-v1-005").branchDiagnostics())
                .extracting(RagIndependentBranchEvaluator.BranchDiagnostic::branch)
                .containsExactly("dense-original", "sparse-original", "expanded-query");
        assertThat(REPORT_PATH).isRegularFile();
    }

    private RagIndependentBranchRuntimeImporter.ImportResult importFixture(
            RagIndependentBranchRuntimeFixture.Fixture fixture
    ) throws Exception {
        MmarcoZhSampledOllamaBatchEmbedder embedder = new MmarcoZhSampledOllamaBatchEmbedder(
                WebClient.builder(), ollamaBaseUrl, embeddingModel, fixture.candidates().size()
        );
        List<float[]> embeddings = embedder.embedAll(fixture.candidates().stream()
                .map(RagIndependentBranchRuntimeFixture.Candidate::content)
                .toList());
        Map<String, RagIndependentBranchRuntimeImporter.EmbeddedCandidate> candidates = new LinkedHashMap<>();
        for (int index = 0; index < fixture.candidates().size(); index++) {
            RagIndependentBranchRuntimeFixture.Candidate candidate = fixture.candidates().get(index);
            VchordBm25ProjectionService.Projection projection = vchordBm25ProjectionService.project(
                    RetrievableTitleLexicalizer.buildSearchText(
                            candidate.title(), candidate.title(), candidate.contentPath(), candidate.sourceName()
                    ),
                    candidate.content()
            );
            candidates.put(candidate.runtimeChunkUuid(), new RagIndependentBranchRuntimeImporter.EmbeddedCandidate(
                    candidate, embeddings.get(index), projection.titleVector(), projection.contentVector(), projection.indexVersion()
            ));
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                RagIndependentBranchRuntimeImporter.ImportResult imported = new RagIndependentBranchRuntimeImporter()
                        .importFixture(connection, OWNER_ID, fixture.fixtureSha256(), List.copyOf(candidates.values()));
                connection.commit();
                return imported;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private RagIndependentBranchEvaluator.VariantRun runVariant(
            String variant,
            String runtimeVariant,
            RagEvaluationDataset dataset,
            RagIndependentBranchReplayLoader.FrozenReplay replay,
            RagIndependentBranchRuntimeFixture.Fixture fixture,
            RagIndependentBranchRuntimeImporter.ImportResult imported
    ) {
        Map<String, RagEvaluationCase> casesById = new LinkedHashMap<>();
        Map<String, RagIndependentBranchReplayLoader.QueryReplay> replayByOriginalQuery = new LinkedHashMap<>();
        for (RagEvaluationCase item : dataset.cases()) {
            casesById.put(item.caseId(), item);
        }
        for (RagIndependentBranchReplayLoader.QueryReplay item : replay.cases()) {
            replayByOriginalQuery.put(item.originalQuery(), item);
        }
        QueryRewriteService frozenRewrite = (kbIds, query, context) -> {
            RagIndependentBranchReplayLoader.QueryReplay queryReplay = replayByOriginalQuery.get(query);
            if (queryReplay == null || !kbIds.equals(List.of(imported.knowledgeBaseId()))) {
                throw new IllegalStateException("运行时评测请求未命中冻结 query replay: " + query);
            }
            RagEvaluationCase evaluationCase = casesById.get(queryReplay.caseId());
            return QueryRewriteResult.builder()
                    .query(queryReplay.originalQuery())
                    .titleQuery(isTitleQuery(evaluationCase))
                    .retrievalQueries(queryReplay.retrievalQueries().stream()
                            .map(RagIndependentBranchReplayLoader.RetrievalQuery::query)
                            .toList())
                    .retrievalQuerySources(queryReplay.retrievalQueries().stream()
                            .map(RagIndependentBranchReplayLoader.RetrievalQuery::source)
                            .toList())
                    .build();
        };
        RagServiceImpl ragService = new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                frozenRewrite,
                vchordBm25QueryService,
                new BgeRerankerService(WebClient.builder(), false, "http://127.0.0.1:8081", 3_000),
                ollamaBaseUrl,
                embeddingModel,
                false,
                false,
                true,
                2_048
        );
        List<RagIndependentBranchEvaluator.QueryReplay> queryReplays = new ArrayList<>();
        for (RagIndependentBranchReplayLoader.QueryReplay queryReplay : replay.cases()) {
            long startedAt = System.nanoTime();
            List<RagRetrievalResult> results = retrieve(
                    ragService,
                    queryReplay,
                    RagIndependentBranchRuntimeScopeMapper.toRuntimeKbScope(
                            queryReplay.kbScope(), imported.knowledgeBaseId()
                    ),
                    runtimeVariant
            );
            RagRouteDecision route = new RagRouter().decide(
                    queryReplay.originalQuery(),
                    RagIndependentBranchRuntimeScopeMapper.toRuntimeKbScope(
                            queryReplay.kbScope(), imported.knowledgeBaseId()
                    ),
                    true,
                    false,
                    true
            );
            if (route.route() == RagRouteDecision.Route.ABSTAIN
                    || route.route() == RagRouteDecision.Route.CLARIFY
                    || route.route() == RagRouteDecision.Route.DIRECT) {
                results = List.of();
            }
            long latencyMs = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            List<String> rankedChunkIds = results.stream()
                    .map(RagRetrievalResult::getChunkId)
                    .map(imported.logicalChunkIdByRuntimeUuid()::get)
                    .toList();
            if (rankedChunkIds.stream().anyMatch(item -> item == null || item.isBlank())) {
                throw new IllegalStateException("隔离运行时返回了未映射的 chunk UUID");
            }
            queryReplays.add(new RagIndependentBranchEvaluator.QueryReplay(
                    queryReplay.caseId(),
                    queryReplay.goldChunkIds(),
                    rankedChunkIds,
                    latencyMs,
                    diagnostics(runtimeVariant, results, rankedChunkIds, queryReplay.goldChunkIds(), imported),
                    queryReplay.shouldAbstain(),
                    results.stream().anyMatch(result -> !imported.knowledgeBaseId().equals(result.getKbId()))
            ));
        }
        return new RagIndependentBranchEvaluator.VariantRun(
                variant,
                fingerprint(variant, replay, fixture),
                queryReplays
        );
    }

    private List<RagRetrievalResult> retrieve(
            RagServiceImpl ragService,
            RagIndependentBranchReplayLoader.QueryReplay replay,
            List<String> runtimeKbScope,
            String runtimeVariant
    ) {
        try {
            Method method = RagServiceImpl.class.getDeclaredMethod(
                    "retrieveForIndependentBranchEvaluation", List.class, String.class, int.class, String.class
            );
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<RagRetrievalResult> results = (List<RagRetrievalResult>) method.invoke(
                    ragService, runtimeKbScope, replay.originalQuery(), TOP_K, runtimeVariant
            );
            return results;
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("独立三路运行时检索失败", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("独立三路运行时检索入口不可用", exception);
        }
    }

    private List<RagIndependentBranchEvaluator.BranchDiagnostic> diagnostics(
            String runtimeVariant,
            List<RagRetrievalResult> results,
            List<String> rankedChunkIds,
            Set<String> goldChunkIds,
            RagIndependentBranchRuntimeImporter.ImportResult imported
    ) {
        return branches(runtimeVariant).stream().map(branch -> {
            Set<String> candidateChunkIds = new LinkedHashSet<>();
            for (RagRetrievalResult result : results) {
                if (matchesBranch(runtimeVariant, branch, result.getRetrievalProvenance())) {
                    candidateChunkIds.add(imported.logicalChunkIdByRuntimeUuid().get(result.getChunkId()));
                }
            }
            Set<String> branchGold = candidateChunkIds.stream()
                    .filter(goldChunkIds::contains)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            int outerRank = rankedChunkIds.stream()
                    .filter(branchGold::contains)
                    .findFirst()
                    .map(chunkId -> rankedChunkIds.indexOf(chunkId) + 1)
                    .orElse(-1);
            return new RagIndependentBranchEvaluator.BranchDiagnostic(
                    branch, candidateChunkIds.size(), candidateChunkIds.size(), branchGold, outerRank
            );
        }).toList();
    }

    private List<String> branches(String runtimeVariant) {
        return switch (runtimeVariant) {
            case "current-flat" -> List.of("current-flat");
            case "two-branch-original" -> List.of("dense-original", "sparse-original");
            case "three-branch-expanded" -> List.of("dense-original", "sparse-original", "expanded-query");
            default -> throw new IllegalArgumentException("未知独立三路运行时变体: " + runtimeVariant);
        };
    }

    private boolean matchesBranch(String runtimeVariant, String branch, List<String> provenance) {
        if ("current-flat".equals(runtimeVariant)) {
            return provenance != null && !provenance.isEmpty();
        }
        return provenance != null && provenance.stream().anyMatch(item -> item.startsWith(branch + ":"));
    }

    private boolean isTitleQuery(RagEvaluationCase evaluationCase) {
        return "title_exact".equals(evaluationCase.queryType())
                || "code_identifier".equals(evaluationCase.queryType())
                || "topic_switch_guard".equals(evaluationCase.queryType())
                || "pdf_page_reference".equals(evaluationCase.queryType());
    }

    private RagIndependentBranchEvaluator.EvaluationFingerprint fingerprint(
            String variant,
            RagIndependentBranchReplayLoader.FrozenReplay replay,
            RagIndependentBranchRuntimeFixture.Fixture fixture
    ) {
        String gold = replay.cases().stream()
                .map(item -> item.caseId() + "=" + item.goldChunkIds().stream().sorted().toList())
                .reduce("", (left, right) -> left + "\n" + right);
        String scope = replay.cases().stream()
                .map(item -> item.caseId() + "=" + item.kbScope())
                .reduce("", (left, right) -> left + "\n" + right);
        String effectiveQuerySet = replay.cases().stream()
                .map(RagIndependentBranchReplayLoader.QueryReplay::caseId)
                .reduce("", (left, right) -> left + "\n" + right);
        return new RagIndependentBranchEvaluator.EvaluationFingerprint(
                replay.datasetId(),
                sha256(gold),
                sha256(scope),
                replay.inputSha256(),
                sha256(variant + "|fixture=" + fixture.fixtureSha256() + "|RRF_K=60|topK=10|candidateBudget=50"),
                TOP_K,
                CANDIDATE_BUDGET,
                sha256(effectiveQuerySet)
        );
    }

    private void writeReport(
            RagIndependentBranchReplayLoader.FrozenReplay replay,
            RagIndependentBranchRuntimeFixture.Fixture fixture,
            RagIndependentBranchRuntimeImporter.ImportResult imported,
            List<RagIndependentBranchEvaluator.VariantRun> runs
    ) throws IOException {
        new RagIndependentBranchReplayRunner().writeReport(replay, runs, REPORT_PATH);
        Map<String, Object> report = objectMapper.readValue(Files.readString(REPORT_PATH), new TypeReference<>() {
        });
        report.put("executionMode", "isolated-real-runtime");
        report.put("fixtureSha256", fixture.fixtureSha256());
        report.put("runtimeChunkMapping", imported.logicalChunkIdByRuntimeUuid());
        Files.writeString(REPORT_PATH, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private RagIndependentBranchEvaluator.QueryReplay queryReplay(
            RagIndependentBranchEvaluator.VariantRun run,
            String caseId
    ) {
        return run.queryReplays().stream()
                .filter(item -> caseId.equals(item.caseId()))
                .findFirst()
                .orElseThrow();
    }

    private void assertIsolatedDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!ISOLATED_JDBC_URL.equals(connection.getMetaData().getURL())
                    || !"jchatmind_rag_eval".equals(connection.getCatalog())) {
                throw new IllegalStateException("TC-G2-10 只能连接 127.0.0.1:55432/jchatmind_rag_eval");
            }
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
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
            VchordBm25QueryService.class,
            VchordBm25ProjectionService.class
    })
    static class RuntimeTestConfig {

    }
}
