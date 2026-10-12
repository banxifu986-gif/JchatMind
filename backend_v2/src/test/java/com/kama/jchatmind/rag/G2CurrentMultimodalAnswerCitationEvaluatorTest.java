package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentMultimodalAnswerCitationEvaluatorTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";

    @Test
    void scoresExactRetrievedGoldCitationsWithoutPromotingCandidate() throws Exception {
        EvaluationInput input = evaluationInput();

        G2CurrentMultimodalAnswerCitationEvaluator.Report report =
                new G2CurrentMultimodalAnswerCitationEvaluator().evaluate(
                        input.profile(), input.runtimePlan(), input.resultsByCaseId(), exactAnswers(input)
                );

        assertThat(report.datasetId()).isEqualTo("g2-current-multimodal-v1");
        assertThat(report.caseCount()).isEqualTo(4);
        assertThat(report.answerableCaseCount()).isEqualTo(3);
        assertThat(report.abstentionCaseCount()).isEqualTo(1);
        assertThat(report.meanCitationPrecision()).isEqualTo(1D);
        assertThat(report.meanCitationRecall()).isEqualTo(1D);
        assertThat(report.exactCitationMatchAccuracy()).isEqualTo(1D);
        assertThat(report.noAnswerCitationCount()).isZero();
        assertThat(report.gate().verdict()).isEqualTo("eligible_for_multimodal_answer_citation_l2");
        assertThat(report.candidateOnly()).isTrue();
        assertThat(report.releaseEligible()).isFalse();
        assertThat(report.answerQualityMeasured()).isFalse();
        assertThat(report.refusalMeasured()).isFalse();
    }

    @Test
    void blocksUnknownAndUnretrievedCitations() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answers = exactAnswers(input);
        G2CurrentMultimodalCandidateFixtureLoader.CandidateCase answerable = firstAnswerable(input.profile());
        String anotherRuntimeId = input.runtimeUuidByLogicalId().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(answerable.goldChunkLogicalId()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        answers.put(answerable.caseId(), "引用: 00000000-0000-0000-0000-000000000000"
                + "\n引用: " + anotherRuntimeId);

        G2CurrentMultimodalAnswerCitationEvaluator.Report report =
                new G2CurrentMultimodalAnswerCitationEvaluator().evaluate(
                        input.profile(), input.runtimePlan(), input.resultsByCaseId(), answers
                );

        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.unmappedCitationCount()).isEqualTo(1);
        assertThat(report.outOfScopeCitationCount()).isEqualTo(1);
        assertThat(report.gate().reasons()).contains(
                "answer_citation_mismatch",
                "citation_not_in_runtime_scope",
                "citation_outside_retrieved_context"
        );
    }

    @Test
    void blocksCitationsOnNoAnswerCases() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answers = exactAnswers(input);
        G2CurrentMultimodalCandidateFixtureLoader.CandidateCase abstention = input.profile().cases().stream()
                .filter(G2CurrentMultimodalCandidateFixtureLoader.CandidateCase::shouldAbstain)
                .findFirst()
                .orElseThrow();
        answers.put(abstention.caseId(), "证据不足。\n引用: "
                + input.runtimeUuidByLogicalId().values().iterator().next());

        G2CurrentMultimodalAnswerCitationEvaluator.Report report =
                new G2CurrentMultimodalAnswerCitationEvaluator().evaluate(
                        input.profile(), input.runtimePlan(), input.resultsByCaseId(), answers
                );

        assertThat(report.noAnswerCitationCount()).isEqualTo(1);
        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.gate().reasons()).contains("citation_on_no_answer_case");
    }

    @Test
    void blocksMalformedAndDuplicateCitations() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answers = exactAnswers(input);
        G2CurrentMultimodalCandidateFixtureLoader.CandidateCase answerable = firstAnswerable(input.profile());
        String exactCitation = answers.get(answerable.caseId());
        answers.put(answerable.caseId(), exactCitation + "\n" + exactCitation + "\n引用: not-a-runtime-id");

        G2CurrentMultimodalAnswerCitationEvaluator.Report report =
                new G2CurrentMultimodalAnswerCitationEvaluator().evaluate(
                        input.profile(), input.runtimePlan(), input.resultsByCaseId(), answers
                );

        assertThat(report.duplicateCitationCount()).isEqualTo(1);
        assertThat(report.malformedCitationMarkerCount()).isEqualTo(1);
        assertThat(report.gate().reasons()).contains("duplicate_citation_marker", "malformed_citation_marker");
    }

    @Test
    void requiresExactAnswerAndRetrievalCaseCoverage() throws Exception {
        EvaluationInput input = evaluationInput();

        assertThatThrownBy(() -> new G2CurrentMultimodalAnswerCitationEvaluator().evaluate(
                input.profile(), input.runtimePlan(), input.resultsByCaseId(), Map.of()
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new G2CurrentMultimodalAnswerCitationEvaluator().evaluate(
                input.profile(), input.runtimePlan(), Map.of(), exactAnswers(input)
        )).isInstanceOf(IllegalStateException.class);
    }

    private Map<String, String> exactAnswers(EvaluationInput input) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : input.profile().cases()) {
            if (candidateCase.shouldAbstain()) {
                answers.put(candidateCase.caseId(), "证据不足，无法确认。");
            } else {
                answers.put(candidateCase.caseId(), "根据资料回答。\n引用: "
                        + input.runtimeUuidByLogicalId().get(candidateCase.goldChunkLogicalId()));
            }
        }
        return answers;
    }

    private G2CurrentMultimodalCandidateFixtureLoader.CandidateCase firstAnswerable(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile
    ) {
        return profile.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .findFirst()
                .orElseThrow();
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
        Map<String, String> runtimeUuidByLogicalId = new LinkedHashMap<>();
        Map<String, List<RagRetrievalResult>> resultsByCaseId = new LinkedHashMap<>();
        for (G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk : runtimePlan.chunks()) {
            runtimeUuidByLogicalId.put(runtimeChunk.chunk().logicalChunkId(), runtimeChunk.chunk().chunkId());
        }
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (candidateCase.shouldAbstain()) {
                resultsByCaseId.put(candidateCase.caseId(), List.of());
                continue;
            }
            RagRetrievalResult result = new RagRetrievalResult();
            result.setChunkId(runtimeUuidByLogicalId.get(candidateCase.goldChunkLogicalId()));
            result.setKbId(profile.assetImportPlan().knowledgeBaseId());
            result.setContent(candidateCase.goldEvidence());
            resultsByCaseId.put(candidateCase.caseId(), List.of(result));
        }
        return new EvaluationInput(profile, runtimePlan, runtimeUuidByLogicalId, resultsByCaseId);
    }

    private record EvaluationInput(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, String> runtimeUuidByLogicalId,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
    }
}
