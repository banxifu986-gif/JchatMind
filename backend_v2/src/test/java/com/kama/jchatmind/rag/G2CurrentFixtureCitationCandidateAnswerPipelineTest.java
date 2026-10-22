package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentFixtureCitationCandidateAnswerPipelineTest {

    @Test
    void candidateReportRemainsCandidateOnlyWhenGenerationIsDisabled() throws Exception {
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate = approvedCandidate();
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        G2CurrentFixtureRuntimeImportPlan.Prepared plan = runtimePlan(fixture);

        G2CurrentFixtureCitationCandidateAnswerPipeline.Report report =
                new G2CurrentFixtureCitationCandidateAnswerPipeline().evaluate(
                        candidate,
                        plan,
                        emptyResults(candidate),
                        emptyResults(candidate),
                        null,
                        null
                );

        assertThat(report.datasetId()).isEqualTo(candidate.datasetId());
        assertThat(report.candidateOnly()).isTrue();
        assertThat(report.releaseEligible()).isFalse();
        assertThat(report.answerGenerationEnabled()).isFalse();
        assertThat(report.fixedGeneration().status()).isEqualTo("disabled");
        assertThat(report.routerGeneration().status()).isEqualTo("disabled");
        assertThat(report.citationGateStatus()).isEqualTo("inconclusive");
        assertThat(report.fixedCitation().gate().verdict()).isEqualTo("inconclusive");
    }

    @Test
    void rejectsPendingCandidateBeforeValidatingOrEvaluatingRuntimeInputs() throws Exception {
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset pending = pendingCandidate();

        assertThatThrownBy(() -> new G2CurrentFixtureCitationCandidateAnswerPipeline().evaluate(
                pending, null, Map.of(), Map.of(), null, null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("通过 review");
    }

    @Test
    void runtimeFlagOffSkipsCandidateAndRuntimeFlagOnLoadsApprovedCandidate() throws Exception {
        assertThat(G2CurrentFixtureRuntimeImportL2Test.loadCitationCandidateIfEnabled(false)).isNull();

        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate =
                G2CurrentFixtureRuntimeImportL2Test.loadCitationCandidateIfEnabled(true);
        assertThat(candidate.reviewStatus()).isEqualTo("approved");
        assertThat(candidate.releaseEligible()).isFalse();
    }

    @Test
    void blocksGenerationWhenAnyRetrievalSafetyCounterIsNonzero() {
        G2CurrentFixtureCitationCandidateAnswerPipeline.requireSafeRetrieval(0, 0, 0);

        assertThatThrownBy(() -> G2CurrentFixtureCitationCandidateAnswerPipeline.requireSafeRetrieval(1, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safety gate");
        assertThatThrownBy(() -> G2CurrentFixtureCitationCandidateAnswerPipeline.requireSafeRetrieval(0, 1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safety gate");
        assertThatThrownBy(() -> G2CurrentFixtureCitationCandidateAnswerPipeline.requireSafeRetrieval(0, 0, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safety gate");
    }

    private G2CurrentFixtureCitationCandidateLoader.CandidateDataset approvedCandidate() throws Exception {
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset pending =
                G2CurrentFixtureCitationCandidateLoader.load(
                        "rag-eval/datasets/manifests/fixture-fast-v2-citation-candidate.json"
                );
        List<G2CurrentFixtureCitationCandidateLoader.CandidateCase> approvedCases = pending.cases().stream()
                .map(item -> new G2CurrentFixtureCitationCandidateLoader.CandidateCase(
                        item.caseId(), item.parentCaseId(), item.query(), item.shouldAbstain(),
                        item.sourceDocumentIds(), item.goldChunkIds(), item.goldCitationGroups(), "approved"))
                .toList();
        return new G2CurrentFixtureCitationCandidateLoader.CandidateDataset(
                pending.datasetId(), pending.parentDatasetId(), pending.status(), "approved",
                pending.caseSha256(), pending.corpusSha256(), approvedCases);
    }

    private G2CurrentFixtureCitationCandidateLoader.CandidateDataset pendingCandidate() throws Exception {
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset approved = approvedCandidate();
        List<G2CurrentFixtureCitationCandidateLoader.CandidateCase> pendingCases = approved.cases().stream()
                .map(item -> new G2CurrentFixtureCitationCandidateLoader.CandidateCase(
                        item.caseId(), item.parentCaseId(), item.query(), item.shouldAbstain(),
                        item.sourceDocumentIds(), item.goldChunkIds(), item.goldCitationGroups(), "pending"))
                .toList();
        return new G2CurrentFixtureCitationCandidateLoader.CandidateDataset(
                approved.datasetId(), approved.parentDatasetId(), approved.status(), "pending",
                approved.caseSha256(), approved.corpusSha256(), pendingCases);
    }

    private G2CurrentFixtureRuntimeImportPlan.Prepared runtimePlan(
            G2CurrentFixtureRuntimeCorpus fixture
    ) {
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);
        return G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);
    }

    private Map<String, List<RagRetrievalResult>> emptyResults(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate
    ) {
        Map<String, List<RagRetrievalResult>> results = new LinkedHashMap<>();
        candidate.cases().forEach(item -> results.put(item.caseId(), List.of()));
        return results;
    }
}
