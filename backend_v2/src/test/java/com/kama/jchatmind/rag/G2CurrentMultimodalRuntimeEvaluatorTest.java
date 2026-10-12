package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentMultimodalRuntimeEvaluatorTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";

    @Test
    void verifiesRoutesPermissionGoldChunksAndAssetLocatorsWithoutPromotingCandidate() throws Exception {
        EvaluationInput input = evaluationInput();

        G2CurrentMultimodalRuntimeEvaluator.Report report =
                new G2CurrentMultimodalRuntimeEvaluator().evaluate(
                        input.profile(), input.runtimePlan(), input.routesByCaseId(), input.resultsByCaseId()
                );

        assertThat(report.datasetId()).isEqualTo("g2-current-multimodal-v1");
        assertThat(report.caseCount()).isEqualTo(4);
        assertThat(report.answerableCaseCount()).isEqualTo(3);
        assertThat(report.abstentionCaseCount()).isEqualTo(1);
        assertThat(report.goldAssetEvidenceHitCount()).isEqualTo(3);
        assertThat(report.routeMismatchCount()).isZero();
        assertThat(report.permissionViolationCount()).isZero();
        assertThat(report.unknownRuntimeChunkCount()).isZero();
        assertThat(report.gate().verdict()).isEqualTo("eligible_for_multimodal_retrieval_l2");
        assertThat(report.candidateOnly()).isTrue();
        assertThat(report.releaseEligible()).isFalse();
        assertThat(report.answerCitationAccuracyMeasured()).isFalse();
        assertThat(report.answerQualityMeasured()).isFalse();
        assertThat(report.refusalMeasured()).isFalse();
    }

    @Test
    void blocksWrongRoutePermissionAndUnknownRuntimeChunk() throws Exception {
        EvaluationInput input = evaluationInput();
        G2CurrentMultimodalCandidateFixtureLoader.CandidateCase answerable = input.profile().cases().stream()
                .filter(item -> !item.shouldAbstain())
                .findFirst()
                .orElseThrow();
        Map<String, String> routes = new LinkedHashMap<>(input.routesByCaseId());
        routes.put(answerable.caseId(), "PRIVATE_RAG");
        Map<String, List<RagRetrievalResult>> results = new LinkedHashMap<>(input.resultsByCaseId());
        RagRetrievalResult gold = results.get(answerable.caseId()).get(0);
        gold.setKbId("foreign-kb");
        RagRetrievalResult unknown = result("00000000-0000-0000-0000-000000000000", "unknown evidence", "foreign-kb");
        results.put(answerable.caseId(), List.of(gold, unknown));

        G2CurrentMultimodalRuntimeEvaluator.Report report =
                new G2CurrentMultimodalRuntimeEvaluator().evaluate(
                        input.profile(), input.runtimePlan(), routes, results
                );

        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.routeMismatchCount()).isEqualTo(1);
        assertThat(report.permissionViolationCount()).isEqualTo(2);
        assertThat(report.unknownRuntimeChunkCount()).isEqualTo(1);
        assertThat(report.gate().reasons()).contains(
                "multimodal_route_mismatch",
                "multimodal_permission_violation",
                "unknown_runtime_chunk"
        );
        assertThat(report.releaseEligible()).isFalse();
    }

    @Test
    void blocksAClaimedGoldChunkWhenAssetLocatorDoesNotMatch() throws Exception {
        EvaluationInput input = evaluationInput();
        G2CurrentMultimodalCandidateFixtureLoader.CandidateCase answerable = input.profile().cases().stream()
                .filter(item -> !item.shouldAbstain())
                .findFirst()
                .orElseThrow();
        Map<String, List<RagRetrievalResult>> results = new LinkedHashMap<>(input.resultsByCaseId());
        RagRetrievalResult gold = results.get(answerable.caseId()).get(0);
        gold.setMetadata(metadata(
                "wrong-asset-id",
                answerable.goldAsset().assetType(),
                answerable.goldAsset().locator()
        ));

        G2CurrentMultimodalRuntimeEvaluator.Report report =
                new G2CurrentMultimodalRuntimeEvaluator().evaluate(
                        input.profile(), input.runtimePlan(), input.routesByCaseId(), results
                );

        assertThat(report.goldAssetEvidenceHitCount()).isEqualTo(2);
        assertThat(report.assetIdMismatchCount()).isEqualTo(1);
        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.gate().reasons()).contains("gold_asset_evidence_not_retrieved");
        assertThat(report.releaseEligible()).isFalse();
    }

    @Test
    void rejectsMismatchedAssetTypeLocatorAndEvidence() throws Exception {
        for (String mismatch : List.of("type", "locator", "evidence")) {
            EvaluationInput input = evaluationInput();
            G2CurrentMultimodalCandidateFixtureLoader.CandidateCase answerable = input.profile().cases().stream()
                    .filter(item -> !item.shouldAbstain())
                    .findFirst()
                    .orElseThrow();
            Map<String, List<RagRetrievalResult>> results = new LinkedHashMap<>(input.resultsByCaseId());
            RagRetrievalResult gold = results.get(answerable.caseId()).get(0);
            if ("type".equals(mismatch)) {
                gold.setMetadata(metadata(
                        assetIdFrom(gold.getMetadata()),
                        answerable.goldAsset().assetType().equals("TABLE") ? "PDF_PAGE_TEXT" : "TABLE",
                        answerable.goldAsset().locator()
                ));
            } else if ("locator".equals(mismatch)) {
                gold.setMetadata(metadata(
                        assetIdFrom(gold.getMetadata()),
                        answerable.goldAsset().assetType(),
                        Map.of("wrongLocator", 1)
                ));
            } else {
                gold.setContent("unrelated content");
            }
            results.put(answerable.caseId(), List.of(gold));

            G2CurrentMultimodalRuntimeEvaluator.Report report =
                    new G2CurrentMultimodalRuntimeEvaluator().evaluate(
                            input.profile(), input.runtimePlan(), input.routesByCaseId(), results
                    );

            assertThat(report.goldAssetEvidenceHitCount()).isEqualTo(2);
            assertThat(report.gate().verdict()).isEqualTo("inconclusive");
            assertThat(report.gate().reasons()).contains("gold_asset_evidence_not_retrieved");
            if ("type".equals(mismatch)) {
                assertThat(report.assetTypeMismatchCount()).isEqualTo(1);
            } else if ("locator".equals(mismatch)) {
                assertThat(report.assetLocatorMismatchCount()).isEqualTo(1);
            } else {
                assertThat(report.goldEvidenceMismatchCount()).isEqualTo(1);
            }
        }
    }

    @Test
    void rejectsIncompleteCaseCoverageAndNonCandidatePlans() throws Exception {
        EvaluationInput input = evaluationInput();

        assertThatThrownBy(() -> new G2CurrentMultimodalRuntimeEvaluator().evaluate(
                input.profile(), input.runtimePlan(), Map.of(), input.resultsByCaseId()
        )).isInstanceOf(IllegalStateException.class);

        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan nonCandidatePlan =
                new G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan(
                        input.runtimePlan().assetImportPlan(), false, input.runtimePlan().chunks()
                );
        assertThatThrownBy(() -> new G2CurrentMultimodalRuntimeEvaluator().evaluate(
                input.profile(), nonCandidatePlan, input.routesByCaseId(), input.resultsByCaseId()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate");
    }

    private EvaluationInput evaluationInput() throws Exception {
        G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile =
                G2CurrentMultimodalCandidateFixtureLoader.load(MANIFEST_PATH);
        int chunkCount = profile.assetImportPlan().chunkRows().size();
        List<float[]> embeddings = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan =
                G2CurrentMultimodalCandidateRuntimeBuilder.build(profile, embeddings, projections);
        Map<String, String> routesByCaseId = new LinkedHashMap<>();
        Map<String, List<RagRetrievalResult>> resultsByCaseId = new LinkedHashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase item : profile.cases()) {
            routesByCaseId.put(item.caseId(), item.expectedRoute());
            if (item.shouldAbstain()) {
                resultsByCaseId.put(item.caseId(), List.of());
                continue;
            }
            G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset asset = profile.assets().stream()
                    .filter(candidate -> candidate.sourceDocumentId().equals(item.sourceDocumentId()))
                    .filter(candidate -> candidate.assetType().equals(item.goldAsset().assetType()))
                    .filter(candidate -> candidate.assetKey().equals(item.goldAsset().assetKey()))
                    .findFirst()
                    .orElseThrow();
            resultsByCaseId.put(item.caseId(), List.of(result(
                    asset.runtimeChunkUuid(),
                    asset.content(),
                    profile.assetImportPlan().knowledgeBaseId(),
                    metadata(asset.runtimeAssetUuid(), asset.assetType(), asset.locator())
            )));
        }
        return new EvaluationInput(profile, runtimePlan, routesByCaseId, resultsByCaseId);
    }

    private RagRetrievalResult result(String chunkId, String content, String kbId) {
        return result(chunkId, content, kbId, "{}");
    }

    private RagRetrievalResult result(String chunkId, String content, String kbId, String metadata) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setContent(content);
        result.setKbId(kbId);
        result.setMetadata(metadata);
        return result;
    }

    private String metadata(String assetId, String assetType, Map<String, Integer> locator) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "asset", Map.of("id", assetId, "type", assetType, "locator", locator)
        ));
    }

    private String assetIdFrom(String metadata) throws Exception {
        return new ObjectMapper().readTree(metadata).path("asset").path("id").asText();
    }

    private record EvaluationInput(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, String> routesByCaseId,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
    }
}
