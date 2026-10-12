package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentFixtureAnswerCitationEvaluatorTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void scoresFinalAnswerChunkReferencesAgainstFrozenGold() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answersByCaseId = new LinkedHashMap<>();
        for (RagEvaluationCase evaluationCase : input.fixture().cases()) {
            String citations = evaluationCase.goldChunkIds().stream()
                    .map(input.runtimeUuidByLogicalId()::get)
                    .map(id -> "引用: " + id)
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
            answersByCaseId.put(
                    evaluationCase.caseId(),
                    evaluationCase.shouldAbstain() ? "证据不足，无法确认。" : "根据资料回答。\n" + citations
            );
        }

        G2CurrentFixtureAnswerCitationEvaluator.Report report =
                new G2CurrentFixtureAnswerCitationEvaluator().evaluate(
                        input.fixture(),
                        input.logicalChunkIdByRuntimeUuid(),
                        input.retrievedRuntimeChunkIdsByCase(),
                        answersByCaseId
                );

        assertThat(report.caseCount()).isEqualTo(20);
        assertThat(report.answerableCaseCount()).isEqualTo(18);
        assertThat(report.noAnswerCaseCount()).isEqualTo(2);
        assertThat(report.meanCitationPrecision()).isEqualTo(1D);
        assertThat(report.meanCitationRecall()).isEqualTo(1D);
        assertThat(report.exactCitationMatchAccuracy()).isEqualTo(1D);
        assertThat(report.noAnswerCitationCount()).isZero();
        assertThat(report.unmappedCitationCount()).isZero();
        assertThat(report.outOfScopeCitationCount()).isZero();
        assertThat(report.gate().verdict()).isEqualTo("eligible_for_answer_citation_ab");
        assertThat(report.answerFaithfulnessMeasured()).isFalse();
    }

    @Test
    void blocksUnknownMalformedAndNoAnswerCitations() throws Exception {
        EvaluationInput input = evaluationInput();
        Map<String, String> answersByCaseId = answersWithExactCitations(input);
        RagEvaluationCase answerable = input.fixture().cases().stream()
                .filter(item -> !item.shouldAbstain())
                .findFirst()
                .orElseThrow();
        RagEvaluationCase noAnswer = input.fixture().cases().stream()
                .filter(RagEvaluationCase::shouldAbstain)
                .findFirst()
                .orElseThrow();
        String firstRuntimeId = input.runtimeUuidByLogicalId().values().iterator().next();
        String inFixtureButNotRetrieved = input.runtimeUuidByLogicalId()
                .get("orders-refund#发货时效#0");
        answersByCaseId.put(answerable.caseId(), "答案\n引用: 00000000-0000-0000-0000-000000000000"
                + "\n引用: not-a-chunk-id\n引用: " + inFixtureButNotRetrieved);
        answersByCaseId.put(noAnswer.caseId(), "证据不足\n引用: " + firstRuntimeId);

        G2CurrentFixtureAnswerCitationEvaluator.Report report =
                new G2CurrentFixtureAnswerCitationEvaluator().evaluate(
                        input.fixture(),
                        input.logicalChunkIdByRuntimeUuid(),
                        input.retrievedRuntimeChunkIdsByCase(),
                        answersByCaseId
                );

        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.unmappedCitationCount()).isEqualTo(1);
        assertThat(report.malformedCitationMarkerCount()).isEqualTo(1);
        assertThat(report.outOfScopeCitationCount()).isEqualTo(2);
        assertThat(report.noAnswerCitationCount()).isEqualTo(1);
        assertThat(report.gate().reasons()).contains(
                "answer_citation_mismatch",
                "citation_not_in_runtime_scope",
                "citation_outside_retrieved_context",
                "malformed_citation_marker",
                "citation_on_no_answer_case"
        );
    }

    @Test
    void requiresAnAnswerEntryForEveryFrozenCase() throws Exception {
        EvaluationInput input = evaluationInput();

        assertThatThrownBy(() -> new G2CurrentFixtureAnswerCitationEvaluator().evaluate(
                input.fixture(),
                input.logicalChunkIdByRuntimeUuid(),
                input.retrievedRuntimeChunkIdsByCase(),
                Map.of()
        )).isInstanceOf(IllegalStateException.class);
    }

    private Map<String, String> answersWithExactCitations(EvaluationInput input) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (RagEvaluationCase evaluationCase : input.fixture().cases()) {
            String citations = evaluationCase.goldChunkIds().stream()
                    .map(input.runtimeUuidByLogicalId()::get)
                    .map(id -> "引用: " + id)
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
            answers.put(evaluationCase.caseId(), evaluationCase.shouldAbstain() ? "证据不足。" : citations);
        }
        return answers;
    }

    private EvaluationInput evaluationInput() throws Exception {
        G2CurrentFixtureRuntimeCorpus fixture = G2CurrentFixtureRuntimeCorpusLoader.load(MANIFEST_PATH);
        int chunkCount = fixture.chunksByLogicalId().size();
        List<float[]> embeddings = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new float[1024])
                .toList();
        List<VchordBm25ProjectionService.Projection> projections = java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1))
                .toList();
        List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates =
                G2CurrentFixtureRuntimeCandidateBuilder.build(fixture, embeddings, projections);
        G2CurrentFixtureRuntimeImportPlan.Prepared plan = G2CurrentFixtureRuntimeImportPlan.prepare(
                fixture, candidates
        );
        Map<String, String> runtimeUuidByLogicalId = new LinkedHashMap<>();
        plan.logicalChunkIdByRuntimeUuid().forEach((runtimeUuid, logicalChunkId) ->
                runtimeUuidByLogicalId.put(logicalChunkId, runtimeUuid)
        );
        Map<String, Set<String>> retrievedRuntimeChunkIdsByCase = new LinkedHashMap<>();
        for (RagEvaluationCase evaluationCase : fixture.cases()) {
            Set<String> retrievedIds = evaluationCase.goldChunkIds().stream()
                    .map(runtimeUuidByLogicalId::get)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            retrievedRuntimeChunkIdsByCase.put(evaluationCase.caseId(), Set.copyOf(retrievedIds));
        }
        return new EvaluationInput(
                fixture,
                plan.logicalChunkIdByRuntimeUuid(),
                runtimeUuidByLogicalId,
                retrievedRuntimeChunkIdsByCase
        );
    }

    private record EvaluationInput(
            G2CurrentFixtureRuntimeCorpus fixture,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, String> runtimeUuidByLogicalId,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCase
    ) {
    }
}
