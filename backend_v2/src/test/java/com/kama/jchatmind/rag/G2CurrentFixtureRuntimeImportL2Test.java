package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.BgeRerankerService;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.service.impl.VchordBm25QueryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "g2.current.fixture.runtime.enabled", matches = "true")
@SpringBootTest(
        classes = G2CurrentFixtureRuntimeImportL2Test.RuntimeTestConfig.class,
        properties = {
                "spring.ai.mcp.client.enabled=false",
                "rag.query-rewrite.llm.enabled=false"
        }
)
@ActiveProfiles("rag-eval")
class G2CurrentFixtureRuntimeImportL2Test {

    private static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String OWNER_ID = "900000000004";
    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";
    private static final String ANSWER_GENERATION_ENABLED_PROPERTY =
            "g2.current.fixture.answer-generation.enabled";
    private static final String ANSWER_GENERATION_MODEL = "deepseek-chat";
    private static final String ANSWER_QUALITY_JUDGE_ENABLED_PROPERTY =
            "g2.current.fixture.answer-quality.judge.enabled";
    private static final String ANSWER_QUALITY_JUDGE_MODEL = "glm-4.6";
    private static final Path REPORT_PATH = Path.of(
            "target", "rag-eval", "current", "fixture-fast-v1-import-l2.json"
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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private ChatClientRegistry chatClientRegistry;

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model:bge-m3:latest}")
    private String embeddingModel;

    @Test
    void importsAndReplaysTheFrozenFixtureOnlyInTheIsolatedDatabase() throws Exception {
        assertIsolatedDatabase();
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(MANIFEST_PATH);
        List<G2CurrentFixtureRuntimeCorpus.Chunk> chunks = fixture.chunksByLogicalId().values().stream()
                .sorted(Comparator.comparing(G2CurrentFixtureRuntimeCorpus.Chunk::logicalChunkId))
                .toList();
        List<float[]> embeddings = new MmarcoZhSampledOllamaBatchEmbedder(
                WebClient.builder(), ollamaBaseUrl, embeddingModel, 8
        ).embedAll(chunks.stream().map(G2CurrentFixtureRuntimeCorpus.Chunk::embeddingText).toList());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ImportedFixture firstImport = transactionTemplate.execute(status -> {
            try {
                List<VchordBm25ProjectionService.Projection> projections = chunks.stream()
                        .map(chunk -> vchordBm25ProjectionService.project(chunk.titleSearchText(), chunk.content()))
                        .toList();
                List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                        G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);
                G2CurrentFixtureRuntimeImportPlan.Prepared plan =
                        G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);
                Connection connection = DataSourceUtils.getConnection(dataSource);
                G2CurrentFixtureRuntimeDatabaseImporter.importFixture(connection, OWNER_ID, plan, candidates);
                return new ImportedFixture(plan, candidates);
            } catch (Exception exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("fixture-fast-v1 隔离 runtime import 失败", exception);
            }
        });

        assertThat(firstImport).isNotNull();
        G2CurrentFixtureRuntimeImportPlan.Prepared replayed = transactionTemplate.execute(status -> {
            try {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                return G2CurrentFixtureRuntimeDatabaseImporter.importFixture(
                        connection,
                        OWNER_ID,
                        firstImport.plan(),
                        firstImport.candidates()
                );
            } catch (Exception exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("fixture-fast-v1 隔离 runtime 重放失败", exception);
            }
        });

        assertThat(replayed).isEqualTo(firstImport.plan());
        assertThat(replayed.datasetId()).isEqualTo(fixture.datasetId());
        writeReport(fixture, replayed, chunks.size());

        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        RuntimeEvaluation runtimeEvaluation =
                compareFixedChainAndRouter(profile, fixture, replayed);
        G2CurrentFixtureRuntimeAblationEvaluator.RuntimeReport runtimeReport = runtimeEvaluation.report();
        assertThat(runtimeReport.caseCount()).isEqualTo(profile.caseCount());
        assertThat(runtimeReport.answerableCaseCount()).isEqualTo(profile.answerableCaseCount());
        assertThat(runtimeReport.router().routeViolations()).isZero();
        assertThat(runtimeReport.router().permissionViolations()).isZero();
        assertThat(runtimeReport.router().externalCalls()).isZero();
        G2CurrentFixtureAnswerEvaluationPipeline.Report answerEvaluation =
                evaluateAnswers(profile, fixture, replayed, runtimeEvaluation);
        writeRuntimeReport(fixture, replayed, runtimeReport, answerEvaluation);
    }

    private RuntimeEvaluation compareFixedChainAndRouter(
            G2CurrentFixtureProfile profile,
            G2CurrentFixtureRuntimeCorpus fixture,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan
    ) {
        RagServiceImpl fixedRagService = createRagService();
        RagServiceImpl routerRagService = createRagService();
        List<G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay> replays = new ArrayList<>();
        Map<String, List<RagRetrievalResult>> fixedResultsByCaseId = new LinkedHashMap<>();
        Map<String, List<RagRetrievalResult>> routerResultsByCaseId = new LinkedHashMap<>();
        for (int index = 0; index < profile.cases().size(); index++) {
            G2CurrentFixtureCase evaluationCase = profile.cases().get(index);
            ArmReplay fixedReplay;
            ArmReplay routerReplay;
            if (index % 2 == 0) {
                fixedReplay = runFixedArm(evaluationCase, fixture, plan, fixedRagService);
                routerReplay = runRouterArm(evaluationCase, plan, routerRagService);
            } else {
                routerReplay = runRouterArm(evaluationCase, plan, routerRagService);
                fixedReplay = runFixedArm(evaluationCase, fixture, plan, fixedRagService);
            }
            fixedResultsByCaseId.put(evaluationCase.caseId(), fixedReplay.results());
            routerResultsByCaseId.put(evaluationCase.caseId(), routerReplay.results());
            replays.add(new G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay(
                    evaluationCase.caseId(), fixedReplay.runtimeArm(), routerReplay.runtimeArm()
            ));
        }
        return new RuntimeEvaluation(
                new G2CurrentFixtureRuntimeAblationEvaluator().evaluate(profile, replays),
                fixedResultsByCaseId,
                routerResultsByCaseId
        );
    }

    private ArmReplay runFixedArm(
            G2CurrentFixtureCase evaluationCase,
            G2CurrentFixtureRuntimeCorpus fixture,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            RagServiceImpl ragService
    ) {
        List<String> authorizedScope = List.of(plan.knowledgeBaseId());
        long startedAt = System.nanoTime();
        List<RagRetrievalResult> results = ragService.retrieve(
                authorizedScope,
                evaluationCase.query(),
                fixture.manifest().defaultTopK()
        );
        return new ArmReplay(
                runtimeArm(
                        "FIXED_CHAIN",
                        fixture.manifest().defaultTopK(),
                        results,
                        authorizedScope,
                        plan,
                        elapsedMillis(startedAt),
                        false,
                        false
                ),
                results
        );
    }

    private ArmReplay runRouterArm(
            G2CurrentFixtureCase evaluationCase,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            RagServiceImpl ragService
    ) {
        List<String> authorizedScope = List.of(plan.knowledgeBaseId());
        RecordingRagRouter ragRouter = new RecordingRagRouter();
        KnowledgeTools knowledgeTools = new KnowledgeTools(ragService, null, ragRouter)
                .fork(
                        OWNER_ID,
                        null,
                        authorizedScope.stream()
                                .map(kbId -> KnowledgeBaseDTO.builder().id(kbId).build())
                                .toList()
                );
        long startedAt = System.nanoTime();
        List<RagRetrievalResult> results = knowledgeTools.retrieveKnowledge(evaluationCase.query(), null);
        long latencyMs = elapsedMillis(startedAt);
        RagRouteDecision decision = ragRouter.recordedDecision();
        if (decision == null) {
            throw new IllegalStateException("KnowledgeTools 未执行 RagRouter");
        }
        List<String> decisionScope = decision.searchScope() == null ? List.of() : decision.searchScope();
        boolean scopeViolation = !authorizedScope.containsAll(decisionScope);
        return new ArmReplay(
                runtimeArm(
                        decision.route().name(),
                        decision.topK(),
                        results,
                        authorizedScope,
                        plan,
                        latencyMs,
                        scopeViolation,
                        decision.route() == RagRouteDecision.Route.EXTERNAL_TOOL
                ),
                results
        );
    }

    private G2CurrentFixtureAnswerEvaluationPipeline.Report evaluateAnswers(
            G2CurrentFixtureProfile profile,
            G2CurrentFixtureRuntimeCorpus fixture,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            RuntimeEvaluation runtimeEvaluation
    ) {
        boolean enabled = Boolean.getBoolean("g2.current.fixture.answer-generation.enabled");
        boolean judgeEnabled = Boolean.getBoolean(ANSWER_QUALITY_JUDGE_ENABLED_PROPERTY);
        String model = "deepseek-chat";
        ChatClient generatorClient = answerGenerator(enabled, model);
        ChatClient judgeClient = answerQualityJudge(judgeEnabled, enabled);
        return new G2CurrentFixtureAnswerEvaluationPipeline().evaluate(
                profile,
                fixture,
                plan,
                runtimeEvaluation.fixedResultsByCaseId(),
                runtimeEvaluation.routerResultsByCaseId(),
                generatorClient,
                enabled ? model : null,
                judgeClient,
                judgeEnabled ? ANSWER_QUALITY_JUDGE_MODEL : null
        );
    }

    private ChatClient answerGenerator(boolean enabled, String model) {
        if (!enabled) {
            return null;
        }
        if (chatClientRegistry == null) {
            throw new IllegalStateException("答案生成已启用，但 ChatClientRegistry 未装配");
        }
        ChatClient client = chatClientRegistry.get(model);
        if (client == null) {
            throw new IllegalStateException("答案生成已启用，但配置的 ChatClient 不存在: " + model);
        }
        return client;
    }

    private ChatClient answerQualityJudge(boolean judgeEnabled, boolean generationEnabled) {
        if (!judgeEnabled) {
            return null;
        }
        if (!generationEnabled) {
            throw new IllegalStateException("answer-quality judge 启用前必须先启用答案生成");
        }
        if (chatClientRegistry == null) {
            throw new IllegalStateException("answer-quality judge 已启用，但 ChatClientRegistry 未装配");
        }
        ChatClient client = chatClientRegistry.get(ANSWER_QUALITY_JUDGE_MODEL);
        if (client == null) {
            throw new IllegalStateException("answer-quality judge 已启用，但配置的 ChatClient 不存在: "
                    + ANSWER_QUALITY_JUDGE_MODEL);
        }
        return client;
    }

    private RagServiceImpl createRagService() {
        return new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                new QueryRewriteServiceImpl(chunkBgeM3Mapper),
                vchordBm25QueryService,
                new BgeRerankerService(WebClient.builder(), false, "http://127.0.0.1:8081", 3_000),
                ollamaBaseUrl,
                embeddingModel,
                false,
                false,
                true,
                2_048
        );
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm runtimeArm(
            String route,
            int selectedTopK,
            List<RagRetrievalResult> results,
            List<String> authorizedScope,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            long latencyMs,
            boolean scopeViolation,
            boolean externalCall
    ) {
        List<String> logicalChunkIds = new ArrayList<>();
        int contextCharacters = 0;
        boolean permissionViolation = scopeViolation;
        for (RagRetrievalResult result : results) {
            if (!authorizedScope.contains(result.getKbId())) {
                permissionViolation = true;
            }
            String logicalChunkId = plan.logicalChunkIdByRuntimeUuid().get(result.getChunkId());
            if (logicalChunkId == null) {
                throw new IllegalStateException("runtime retrieval 返回未映射的 chunk UUID: " + result.getChunkId());
            }
            logicalChunkIds.add(logicalChunkId);
            contextCharacters += result.getContent() == null ? 0 : result.getContent().length();
        }
        return new G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm(
                route,
                selectedTopK,
                logicalChunkIds,
                latencyMs,
                contextCharacters,
                permissionViolation,
                externalCall
        );
    }

    private void assertIsolatedDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!ISOLATED_JDBC_URL.equals(connection.getMetaData().getURL())
                    || !"jchatmind_rag_eval".equals(connection.getCatalog())) {
                throw new IllegalStateException("TC-G2 current fixture import 仅允许隔离 jchatmind_rag_eval");
            }
        }
    }

    private void writeReport(
            G2CurrentFixtureRuntimeCorpus fixture,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            int candidateCount
    ) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executionMode", "isolated-postgres-real-import");
        report.put("datasetId", fixture.datasetId());
        report.put("caseSha256", fixture.caseSha256());
        report.put("corpusSha256", fixture.corpusSha256());
        report.put("fixtureSha256", plan.fixtureSha256());
        report.put("knowledgeBaseId", plan.knowledgeBaseId());
        report.put("candidateCount", candidateCount);
        report.put("idempotentReplay", true);
        Files.writeString(REPORT_PATH, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private void writeRuntimeReport(
            G2CurrentFixtureRuntimeCorpus fixture,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            G2CurrentFixtureRuntimeAblationEvaluator.RuntimeReport runtimeReport,
            G2CurrentFixtureAnswerEvaluationPipeline.Report answerEvaluation
    ) throws IOException {
        Path reportPath = Path.of(
                "target", "rag-eval", "router", "fixture-fast-v1-runtime-router-ablation.json"
        );
        Files.createDirectories(reportPath.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executionMode", "isolated-real-runtime");
        report.put("datasetId", fixture.datasetId());
        report.put("caseSha256", fixture.caseSha256());
        report.put("corpusSha256", fixture.corpusSha256());
        report.put("fixtureSha256", plan.fixtureSha256());
        report.put("knowledgeBaseId", plan.knowledgeBaseId());
        report.put("runtimeChunkMapping", plan.logicalChunkIdByRuntimeUuid());
        report.put("queryRewriteLlmEnabled", false);
        report.put("rerankEnabled", false);
        report.put("costProxy", "retrieved_context_characters");
        report.put("answerQualityJudgeEnabled", answerEvaluation.answerQualityJudgeEnabled());
        report.put("answerQualityJudgeModel", answerEvaluation.judgeModel());
        report.put("answerGenerationEnabled", answerEvaluation.answerGenerationEnabled());
        report.put("answerGenerationModel", answerEvaluation.generatorModel());
        report.put("fixedAnswerGeneration", answerEvaluation.fixedGeneration());
        report.put("routerAnswerGeneration", answerEvaluation.routerGeneration());
        report.put("answerQualityMeasured", answerEvaluation.answerQualityMeasured());
        report.put("fixedAnswerQuality", answerEvaluation.fixedQuality());
        report.put("routerAnswerQuality", answerEvaluation.routerQuality());
        report.put("pairedAnswerQualityComparison", answerEvaluation.pairedQuality());
        report.put("answerRefusalMeasured", answerEvaluation.answerRefusalMeasured());
        report.put("fixedAnswerRefusal", answerEvaluation.fixedRefusal());
        report.put("routerAnswerRefusal", answerEvaluation.routerRefusal());
        report.put("pairedAnswerRefusalComparison", answerEvaluation.pairedRefusal());
        report.put("answerCitationAccuracyMeasured", answerEvaluation.answerCitationAccuracyMeasured());
        report.put("fixedAnswerCitationEvaluation", answerEvaluation.fixedCitation());
        report.put("routerAnswerCitationEvaluation", answerEvaluation.routerCitation());
        report.put("defaultSwitchGate", new G2CurrentFixtureDefaultSwitchGate().evaluate(
                new G2CurrentFixtureDefaultSwitchGate.Evidence(
                        runtimeReport.gate().verdict(),
                        answerEvaluation.fixedCitation().gate().verdict(),
                        answerEvaluation.routerCitation().gate().verdict(),
                        answerEvaluation.pairedQuality() == null
                                ? "not_configured" : answerEvaluation.pairedQuality().gateStatus(),
                        answerEvaluation.pairedRefusal() == null
                                ? "not_configured" : answerEvaluation.pairedRefusal().gateStatus(),
                        false,
                        "not_run"
                )
        ));
        report.put("runtimeComparison", runtimeReport);
        Files.writeString(reportPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
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
            VchordBm25ProjectionService.class,
            VchordBm25QueryService.class,
            ChatClientRegistry.class,
            AnswerGenerationRuntimeConfig.class,
            AnswerQualityJudgeRuntimeConfig.class
    })
    static class RuntimeTestConfig {
    }

    @Configuration
    @ConditionalOnProperty(name = ANSWER_GENERATION_ENABLED_PROPERTY, havingValue = "true")
    @ImportAutoConfiguration(DeepSeekChatAutoConfiguration.class)
    static class AnswerGenerationRuntimeConfig {

        @Bean(ANSWER_GENERATION_MODEL)
        @ConditionalOnBean(DeepSeekChatModel.class)
        ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
            return ChatClient.create(deepSeekChatModel);
        }
    }

    @Configuration
    @ConditionalOnProperty(
            name = {ANSWER_GENERATION_ENABLED_PROPERTY, ANSWER_QUALITY_JUDGE_ENABLED_PROPERTY},
            havingValue = "true"
    )
    @ImportAutoConfiguration(ZhiPuAiChatAutoConfiguration.class)
    static class AnswerQualityJudgeRuntimeConfig {

        @Bean(ANSWER_QUALITY_JUDGE_MODEL)
        @ConditionalOnBean(ZhiPuAiChatModel.class)
        ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
            return ChatClient.create(zhiPuAiChatModel);
        }
    }

    private record ImportedFixture(
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) {
    }

    private record ArmReplay(
            G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm runtimeArm,
            List<RagRetrievalResult> results
    ) {
        private ArmReplay {
            results = List.copyOf(results);
        }
    }

    private record RuntimeEvaluation(
            G2CurrentFixtureRuntimeAblationEvaluator.RuntimeReport report,
            Map<String, List<RagRetrievalResult>> fixedResultsByCaseId,
            Map<String, List<RagRetrievalResult>> routerResultsByCaseId
    ) {
        private RuntimeEvaluation {
            fixedResultsByCaseId = immutableResults(fixedResultsByCaseId);
            routerResultsByCaseId = immutableResults(routerResultsByCaseId);
        }

        private static Map<String, List<RagRetrievalResult>> immutableResults(
                Map<String, List<RagRetrievalResult>> results
        ) {
            Map<String, List<RagRetrievalResult>> immutable = new LinkedHashMap<>();
            results.forEach((caseId, caseResults) -> immutable.put(caseId, List.copyOf(caseResults)));
            return Map.copyOf(immutable);
        }
    }

    private static final class RecordingRagRouter extends RagRouter {

        private RagRouteDecision decision;

        @Override
        public RagRouteDecision decide(
                String query,
                List<String> allowedKbIds,
                boolean authorized,
                boolean externalAllowed,
                boolean privateEvidenceAvailable
        ) {
            decision = super.decide(query, allowedKbIds, authorized, externalAllowed, privateEvidenceAvailable);
            return decision;
        }

        private RagRouteDecision recordedDecision() {
            return decision;
        }
    }
}
