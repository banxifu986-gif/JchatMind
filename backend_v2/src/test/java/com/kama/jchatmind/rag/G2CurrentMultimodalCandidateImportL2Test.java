package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.config.ChatClientRegistry;
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
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "g2.current.multimodal.candidate.import.enabled", matches = "true")
@SpringBootTest(
        classes = {
                G2CurrentFixtureRuntimeImportL2Test.RuntimeTestConfig.class,
                G2CurrentMultimodalCandidateImportL2Test.AnswerGenerationRuntimeConfig.class,
                G2CurrentMultimodalCandidateImportL2Test.RefusalJudgeRuntimeConfig.class
        },
        properties = {
                "spring.ai.mcp.client.enabled=false",
                "rag.query-rewrite.llm.enabled=false"
        }
)
@ActiveProfiles("rag-eval")
class G2CurrentMultimodalCandidateImportL2Test {

    private static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String OWNER_ID = "900000000004";
    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";
    private static final String ANSWER_GENERATION_ENABLED_PROPERTY =
            "g2.current.multimodal.answer-generation.enabled";
    private static final String ANSWER_GENERATION_MODEL = "deepseek-chat";
    private static final String ANSWER_GENERATION_CLIENT = "deepseek-multimodal";
    private static final String REFUSAL_JUDGE_ENABLED_PROPERTY =
            "g2.current.multimodal.answer-refusal-judge.enabled";
    private static final String REFUSAL_JUDGE_MODEL = "glm-4.6";
    private static final String REFUSAL_JUDGE_CLIENT = "glm-multimodal-refusal";
    private static final Path REPORT_PATH = Path.of(
            "target", "rag-eval", "multimodal", "g2-current-multimodal-v1-runtime.json"
    );

    @Autowired
    private DataSource dataSource;

    @Autowired
    private VchordBm25ProjectionService vchordBm25ProjectionService;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private VchordBm25QueryService vchordBm25QueryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private ChatClientRegistry chatClientRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model:bge-m3:latest}")
    private String embeddingModel;

    @Value("${g2.current.multimodal.answer-generation.enabled:false}")
    private boolean answerGenerationEnabled;

    @Value("${g2.current.multimodal.answer-refusal-judge.enabled:false}")
    private boolean refusalJudgeEnabled;

    @Test
    void importsAndReplaysCandidateRowsOnlyInTheIsolatedDatabase() throws Exception {
        ChatClient generatorClient = answerGenerator();
        ChatClient refusalJudgeClient = refusalJudge();
        assertIsolatedDatabase();
        assertCandidateAssetSchema();
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);
        List<G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunks =
                profile.assetImportPlan().chunkRows();
        List<float[]> embeddings = new MmarcoZhSampledOllamaBatchEmbedder(
                WebClient.builder(), ollamaBaseUrl, embeddingModel, 8
        ).embedAll(chunks.stream()
                .map(G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow::embeddingText)
                .toList());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan =
                transactionTemplate.execute(status -> {
                    try {
                        List<VchordBm25ProjectionService.Projection> projections = chunks.stream()
                                .map(chunk -> vchordBm25ProjectionService.project(
                                        chunk.titleSearchText(), chunk.content()
                                ))
                                .toList();
                        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan prepared =
                                G2CurrentMultimodalCandidateRuntimeBuilder.build(
                                        profile, embeddings, projections
                                );
                        Connection connection = DataSourceUtils.getConnection(dataSource);
                        G2CurrentMultimodalCandidateDatabaseImporter.importCandidate(
                                connection, OWNER_ID, prepared
                        );
                        return prepared;
                    } catch (Exception exception) {
                        status.setRollbackOnly();
                        throw new IllegalStateException("multimodal candidate 隔离 runtime import 失败", exception);
                    }
                });

        assertThat(runtimePlan).isNotNull();
        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan replayed =
                transactionTemplate.execute(status -> {
                    try {
                        Connection connection = DataSourceUtils.getConnection(dataSource);
                        G2CurrentMultimodalCandidateDatabaseImporter.importCandidate(
                                connection, OWNER_ID, runtimePlan
                        );
                        return runtimePlan;
                    } catch (Exception exception) {
                        status.setRollbackOnly();
                        throw new IllegalStateException("multimodal candidate 隔离 runtime 重放失败", exception);
                    }
                });
        assertThat(replayed).isEqualTo(runtimePlan);
        assertThat(replayed.assetImportPlan().candidateOnly()).isTrue();
        assertThat(replayed.assetImportPlan().knowledgeBaseMetadata().get("candidateOnly")).isEqualTo(true);
        assertThat(replayed.assetImportPlan().documentRows()).hasSize(2);
        assertThat(replayed.assetImportPlan().assetRows()).hasSize(2);

        RuntimeRetrieval runtimeRetrieval = evaluateRuntimeRetrieval(profile, replayed);
        G2CurrentMultimodalRuntimeEvaluator.Report retrievalReport = runtimeRetrieval.report();
        G2CurrentMultimodalAnswerGenerationEvaluator.Report answerGeneration =
                new G2CurrentMultimodalAnswerGenerationEvaluator(
                        generatorClient, answerGenerationEnabled ? ANSWER_GENERATION_MODEL : null
                ).generate(profile, replayed, runtimeRetrieval.resultsByCaseId());
        G2CurrentMultimodalAnswerCitationEvaluator.Report answerCitation = answerGenerationEnabled
                ? new G2CurrentMultimodalAnswerCitationEvaluator().evaluate(
                profile, replayed, runtimeRetrieval.resultsByCaseId(), answerGeneration.finalAnswersByCaseId()
        ) : null;
        boolean answerCitationAccuracyMeasured = answerGenerationEnabled
                && answerGeneration.failedCaseCount() == 0
                && answerGeneration.skippedCaseCount() == 0;
        G2CurrentMultimodalRefusalEvaluator.Report answerRefusal =
                new G2CurrentMultimodalRefusalEvaluator(
                        refusalJudgeClient, refusalJudgeEnabled ? REFUSAL_JUDGE_MODEL : null
                ).evaluate(profile, replayed, runtimeRetrieval.resultsByCaseId(),
                        answerGeneration.finalAnswersByCaseId());
        boolean answerRefusalMeasured = refusalJudgeEnabled
                && answerRefusal.evaluatedCaseCount() == answerRefusal.noAnswerCaseCount();
        writeRuntimeReport(
                profile,
                replayed,
                retrievalReport,
                answerGeneration,
                answerCitation,
                answerCitationAccuracyMeasured,
                answerRefusal,
                answerRefusalMeasured
        );
        assertThat(retrievalReport.caseCount()).isEqualTo(4);
        assertThat(retrievalReport.goldAssetEvidenceHitCount()).isEqualTo(3);
        assertThat(retrievalReport.routeMismatchCount()).isZero();
        assertThat(retrievalReport.permissionViolationCount()).isZero();
        assertThat(retrievalReport.unknownRuntimeChunkCount()).isZero();
        assertThat(retrievalReport.gate().verdict()).isEqualTo("eligible_for_multimodal_retrieval_l2");
        assertThat(retrievalReport.candidateOnly()).isTrue();
        assertThat(retrievalReport.releaseEligible()).isFalse();
        assertThat(retrievalReport.answerCitationAccuracyMeasured()).isFalse();
        assertThat(retrievalReport.answerQualityMeasured()).isFalse();
        assertThat(retrievalReport.refusalMeasured()).isFalse();
        if (answerGenerationEnabled) {
            assertThat(answerGeneration.generationCallCount()).isEqualTo(4);
            assertThat(answerGeneration.failedCaseCount()).isZero();
            assertThat(answerGeneration.skippedCaseCount()).isZero();
            assertThat(answerCitationAccuracyMeasured).isTrue();
            assertThat(answerCitation.gate().verdict())
                    .isEqualTo("eligible_for_multimodal_answer_citation_l2");
        } else {
            assertThat(answerGeneration.generationCallCount()).isZero();
            assertThat(answerCitation).isNull();
            assertThat(answerCitationAccuracyMeasured).isFalse();
        }
        if (refusalJudgeEnabled) {
            assertThat(answerRefusal.noAnswerCaseCount()).isEqualTo(1);
            assertThat(answerRefusal.evaluatedCaseCount()).isEqualTo(1);
            assertThat(answerRefusal.skippedCaseCount()).isZero();
            assertThat(answerRefusalMeasured).isTrue();
        } else {
            assertThat(answerRefusal.judgeCallCount()).isZero();
            assertThat(answerRefusalMeasured).isFalse();
        }
    }

    private RuntimeRetrieval evaluateRuntimeRetrieval(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) {
        RagServiceImpl ragService = new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                new QueryRewriteServiceImpl(chunkBgeM3Mapper),
                vchordBm25QueryService,
                new BgeRerankerService(WebClient.builder(), false, "http://127.0.0.1:8081", 3_000),
                ollamaBaseUrl,
                embeddingModel,
                false,
                true,
                true,
                2_048
        );
        List<String> authorizedScope = List.of(runtimePlan.assetImportPlan().knowledgeBaseId());
        List<KnowledgeBaseDTO> allowedKnowledgeBases = authorizedScope.stream()
                .map(kbId -> KnowledgeBaseDTO.builder().id(kbId).build())
                .toList();
        Map<String, String> routesByCaseId = new LinkedHashMap<>();
        Map<String, List<RagRetrievalResult>> resultsByCaseId = new LinkedHashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            RecordingRagRouter ragRouter = new RecordingRagRouter();
            KnowledgeTools knowledgeTools = new KnowledgeTools(ragService, null, ragRouter)
                    .fork(OWNER_ID, null, allowedKnowledgeBases);
            List<RagRetrievalResult> results = knowledgeTools.retrieveKnowledge(candidateCase.query(), null);
            RagRouteDecision route = ragRouter.recordedDecision();
            if (route == null) {
                throw new IllegalStateException("KnowledgeTools 未执行多模态候选 RagRouter");
            }
            routesByCaseId.put(candidateCase.caseId(), route.route().name());
            resultsByCaseId.put(candidateCase.caseId(), results);
        }
        return new RuntimeRetrieval(
                new G2CurrentMultimodalRuntimeEvaluator().evaluate(
                        profile, runtimePlan, routesByCaseId, resultsByCaseId
                ),
                resultsByCaseId
        );
    }

    private ChatClient answerGenerator() {
        if (!answerGenerationEnabled) {
            return null;
        }
        if (chatClientRegistry == null) {
            throw new IllegalStateException("multimodal answer generation enabled but ChatClientRegistry is missing");
        }
        ChatClient client = chatClientRegistry.get(ANSWER_GENERATION_CLIENT);
        if (client == null) {
            throw new IllegalStateException("multimodal answer generation enabled but DeepSeek client is missing");
        }
        return client;
    }

    private ChatClient refusalJudge() {
        if (!refusalJudgeEnabled) {
            return null;
        }
        if (!answerGenerationEnabled) {
            throw new IllegalStateException("multimodal refusal judge requires answer generation to be enabled");
        }
        if (chatClientRegistry == null) {
            throw new IllegalStateException("multimodal refusal judge enabled but ChatClientRegistry is missing");
        }
        ChatClient client = chatClientRegistry.get(REFUSAL_JUDGE_CLIENT);
        if (client == null) {
            throw new IllegalStateException("multimodal refusal judge enabled but GLM client is missing");
        }
        return client;
    }

    private void writeRuntimeReport(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            G2CurrentMultimodalRuntimeEvaluator.Report retrievalReport,
            G2CurrentMultimodalAnswerGenerationEvaluator.Report answerGeneration,
            G2CurrentMultimodalAnswerCitationEvaluator.Report answerCitation,
            boolean answerCitationAccuracyMeasured,
            G2CurrentMultimodalRefusalEvaluator.Report answerRefusal,
            boolean answerRefusalMeasured
    ) throws Exception {
        Files.createDirectories(REPORT_PATH.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executionMode", "isolated-postgres-real-runtime");
        report.put("datasetId", profile.datasetId());
        report.put("manifestSha256", profile.manifestSha256());
        report.put("caseSha256", profile.caseSha256());
        report.put("corpusSha256", profile.corpusSha256());
        report.put("knowledgeBaseId", runtimePlan.assetImportPlan().knowledgeBaseId());
        report.put("runtimeChunkMapping", runtimePlan.chunks().stream().collect(
                java.util.stream.Collectors.toMap(
                        chunk -> chunk.chunk().chunkId(),
                        chunk -> chunk.chunk().logicalChunkId(),
                        (left, right) -> left,
                        LinkedHashMap::new
                )
        ));
        report.put("candidateOnly", retrievalReport.candidateOnly());
        report.put("releaseEligible", retrievalReport.releaseEligible());
        report.put("idempotentReplay", true);
        report.put("retrievalEvaluation", retrievalReport);
        report.put("answerGenerationEnabled", answerGenerationEnabled);
        report.put("answerGeneration", answerGeneration);
        report.put("answerCitationAccuracyMeasured", answerCitationAccuracyMeasured);
        report.put("answerCitationEvaluation", answerCitation);
        report.put("answerRefusalJudgeEnabled", refusalJudgeEnabled);
        report.put("answerRefusalMeasured", answerRefusalMeasured);
        report.put("answerRefusalEvaluation", answerRefusal);
        Files.writeString(REPORT_PATH, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @Configuration
    @ConditionalOnProperty(name = ANSWER_GENERATION_ENABLED_PROPERTY, havingValue = "true")
    @ImportAutoConfiguration(DeepSeekChatAutoConfiguration.class)
    static class AnswerGenerationRuntimeConfig {

        @Bean(ANSWER_GENERATION_CLIENT)
        @ConditionalOnBean(DeepSeekChatModel.class)
        ChatClient multimodalDeepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
            return ChatClient.create(deepSeekChatModel);
        }
    }

    @Configuration
    @ConditionalOnProperty(
            name = {ANSWER_GENERATION_ENABLED_PROPERTY, REFUSAL_JUDGE_ENABLED_PROPERTY},
            havingValue = "true"
    )
    @ImportAutoConfiguration(ZhiPuAiChatAutoConfiguration.class)
    static class RefusalJudgeRuntimeConfig {

        @Bean(REFUSAL_JUDGE_CLIENT)
        @ConditionalOnBean(ZhiPuAiChatModel.class)
        ChatClient multimodalZhiPuAiRefusalJudge(ZhiPuAiChatModel zhiPuAiChatModel) {
            return ChatClient.create(zhiPuAiChatModel);
        }
    }

    private record RuntimeRetrieval(
            G2CurrentMultimodalRuntimeEvaluator.Report report,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
        private RuntimeRetrieval {
            Map<String, List<RagRetrievalResult>> immutableResults = new LinkedHashMap<>();
            resultsByCaseId.forEach((caseId, results) -> immutableResults.put(caseId, List.copyOf(results)));
            resultsByCaseId = Map.copyOf(immutableResults);
        }
    }

    private void assertIsolatedDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(ISOLATED_JDBC_URL);
            assertThat(connection.getCatalog()).isEqualTo("jchatmind_rag_eval");
            assertThat(connection.getAutoCommit()).isTrue();
            try (PreparedStatement statement = connection.prepareStatement("SELECT current_database()")) {
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("jchatmind_rag_eval");
                }
            }
        }
    }

    private void assertCandidateAssetSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT to_regclass('public.document_asset') IS NOT NULL,
                           to_regclass('public.document_asset_chunk') IS NOT NULL
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1))
                            .as("isolated evaluation database must have document_asset; initialize an empty volume with 003-document-assets.sql")
                            .isTrue();
                    assertThat(result.getBoolean(2))
                            .as("isolated evaluation database must have document_asset_chunk; initialize an empty volume with 003-document-assets.sql")
                            .isTrue();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (
                               SELECT 1 FROM pg_constraint
                               WHERE conname = 'uq_chunk_bge_m3_id_doc'
                                 AND conrelid = to_regclass('public.chunk_bge_m3')
                           ),
                           EXISTS (
                               SELECT 1 FROM pg_constraint
                               WHERE conname = 'fk_document_asset_chunk_asset'
                                 AND conrelid = to_regclass('public.document_asset_chunk')
                           ),
                           EXISTS (
                               SELECT 1 FROM pg_constraint
                               WHERE conname = 'fk_document_asset_chunk_chunk'
                                 AND conrelid = to_regclass('public.document_asset_chunk')
                           ),
                           EXISTS (
                               SELECT 1 FROM pg_constraint
                               WHERE conname = 'chk_document_asset_chunk_same_document'
                                 AND conrelid = to_regclass('public.document_asset_chunk')
                           )
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    for (int column = 1; column <= 4; column++) {
                        assertThat(result.getBoolean(column))
                                .as("isolated candidate asset schema is missing constraint column %s", column)
                                .isTrue();
                    }
                }
            }
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
