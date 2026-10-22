package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class G2CurrentFixtureCitationCandidateEvaluatorTest {

    @Test
    void acceptsOneEquivalentCitationPerRequiredEvidenceGroup() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answers = answersWithFirstGoldOption(input);
        G2CurrentFixtureCitationCandidateLoader.CandidateCase equivalentSourceCase = input.candidate()
                .caseById("fixture-fast-v2-citation-candidate-002");
        answers.put(equivalentSourceCase.caseId(), "引用: " + input.runtimeUuidByLogicalId().get(
                "logistics#发货与物流>发货时效>普通商品#0"
        ));

        G2CurrentFixtureCitationCandidateLoader.CandidateCase crossDocumentCase = input.candidate()
                .caseById("fixture-fast-v2-citation-candidate-012");
        answers.put(crossDocumentCase.caseId(), crossDocumentCase.goldCitationGroups().stream()
                .map(group -> group.get(0))
                .map(input.runtimeUuidByLogicalId()::get)
                .map(id -> "引用: " + id)
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow());

        G2CurrentFixtureCitationCandidateEvaluator.Report report =
                new G2CurrentFixtureCitationCandidateEvaluator().evaluate(
                        input.candidate(),
                        input.logicalChunkIdByRuntimeUuid(),
                        input.retrievedRuntimeChunkIdsByCase(),
                        answers
                );

        assertThat(report.datasetId()).isEqualTo("fixture-fast-v2-citation-candidate");
        assertThat(report.candidateOnly()).isTrue();
        assertThat(report.releaseEligible()).isFalse();
        assertThat(report.meanCitationPrecision()).isEqualTo(1D);
        assertThat(report.meanCitationRecall()).isEqualTo(1D);
        assertThat(report.exactCitationMatchAccuracy()).isEqualTo(1D);
        assertThat(report.gate().verdict()).isEqualTo("eligible_for_candidate_citation_contract");
    }

    @Test
    void requiresEveryEvidenceGroupAndRejectsMultipleAlternativesFromOneGroup() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answers = answersWithFirstGoldOption(input);
        G2CurrentFixtureCitationCandidateLoader.CandidateCase crossDocumentCase = input.candidate()
                .caseById("fixture-fast-v2-citation-candidate-012");
        answers.put(crossDocumentCase.caseId(), "引用: " + input.runtimeUuidByLogicalId().get(
                "logistics#发货与物流>订单退款#0"
        ));
        G2CurrentFixtureCitationCandidateLoader.CandidateCase duplicateAlternativeCase = input.candidate()
                .caseById("fixture-fast-v2-citation-candidate-002");
        answers.put(duplicateAlternativeCase.caseId(), "引用: " + input.runtimeUuidByLogicalId().get(
                "orders-refund#发货时效#0"
        ) + "\n引用: " + input.runtimeUuidByLogicalId().get(
                "logistics#发货与物流>发货时效>普通商品#0"
        ));

        G2CurrentFixtureCitationCandidateEvaluator.Report report =
                new G2CurrentFixtureCitationCandidateEvaluator().evaluate(
                        input.candidate(),
                        input.logicalChunkIdByRuntimeUuid(),
                        input.retrievedRuntimeChunkIdsByCase(),
                        answers
                );

        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.caseById(crossDocumentCase.caseId()).recall()).isEqualTo(0.5D);
        assertThat(report.caseById(crossDocumentCase.caseId()).exactMatch()).isFalse();
        assertThat(report.caseById(duplicateAlternativeCase.caseId()).exactMatch()).isFalse();
    }

    @Test
    void rejectsNoAnswerAndUnretrievedCitations() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answers = answersWithFirstGoldOption(input);
        G2CurrentFixtureCitationCandidateLoader.CandidateCase answerableCase = input.candidate()
                .caseById("fixture-fast-v2-citation-candidate-001");
        String outOfScopeId = input.runtimeUuidByLogicalId().get("orders-refund#会员积分#0");
        answers.put(answerableCase.caseId(), "答案\n引用: " + outOfScopeId);
        Map<String, Set<String>> retrieved = new LinkedHashMap<>(input.retrievedRuntimeChunkIdsByCase());
        Set<String> retrievedForAnswerableCase = new LinkedHashSet<>(retrieved.get(answerableCase.caseId()));
        retrievedForAnswerableCase.remove(outOfScopeId);
        retrieved.put(answerableCase.caseId(), Set.copyOf(retrievedForAnswerableCase));
        G2CurrentFixtureCitationCandidateLoader.CandidateCase noAnswerCase = input.candidate()
                .caseById("fixture-fast-v2-citation-candidate-019");
        answers.put(noAnswerCase.caseId(), "证据不足。\n引用: " + outOfScopeId);

        G2CurrentFixtureCitationCandidateEvaluator.Report report =
                new G2CurrentFixtureCitationCandidateEvaluator().evaluate(
                        input.candidate(), input.logicalChunkIdByRuntimeUuid(), retrieved, answers);

        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.noAnswerCitationCount()).isEqualTo(1);
        assertThat(report.outOfScopeCitationCount()).isEqualTo(1);
        assertThat(report.gate().reasons()).contains(
                "citation_on_no_answer_case", "citation_outside_retrieved_context");
    }

    private Map<String, String> answersWithFirstGoldOption(EvaluationInput input) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase evaluationCase : input.candidate().cases()) {
            String citations = evaluationCase.goldCitationGroups().stream()
                    .map(group -> group.get(0))
                    .map(input.runtimeUuidByLogicalId()::get)
                    .map(id -> "引用: " + id)
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
            answers.put(evaluationCase.caseId(), evaluationCase.shouldAbstain()
                    ? "证据不足，无法确认。" : citations);
        }
        return answers;
    }

    private EvaluationInput evaluationInput() throws Exception {
        String manifestPath = "rag-eval/datasets/manifests/fixture-fast-v2-citation-candidate.json";
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate =
                G2CurrentFixtureCitationCandidateLoader.load(manifestPath);
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> runtimeCandidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);
        G2CurrentFixtureRuntimeImportPlan.Prepared plan = G2CurrentFixtureRuntimeImportPlan.prepare(
                fixture, runtimeCandidates
        );
        Map<String, String> runtimeUuidByLogicalId = new LinkedHashMap<>();
        plan.logicalChunkIdByRuntimeUuid().forEach((runtimeUuid, logicalChunkId) ->
                runtimeUuidByLogicalId.put(logicalChunkId, runtimeUuid)
        );
        Map<String, Set<String>> retrievedIdsByCase = new LinkedHashMap<>();
        Set<String> everyRuntimeId = new LinkedHashSet<>(plan.logicalChunkIdByRuntimeUuid().keySet());
        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase evaluationCase : candidate.cases()) {
            retrievedIdsByCase.put(evaluationCase.caseId(), Set.copyOf(everyRuntimeId));
        }
        return new EvaluationInput(candidate, plan.logicalChunkIdByRuntimeUuid(), runtimeUuidByLogicalId,
                retrievedIdsByCase);
    }

    private record EvaluationInput(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, String> runtimeUuidByLogicalId,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCase
    ) {
    }
}
