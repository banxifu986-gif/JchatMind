package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class G2CurrentMultimodalRefusalEvaluatorTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";

    @Test
    void judgesOnlyCandidateNoAnswerCaseAndReportsAbstentionAccuracy() throws Exception {
        EvaluationInput input = evaluationInput();
        GeneratorFixture judge = judgeClient("{\"correctAbstention\":true}");

        G2CurrentMultimodalRefusalEvaluator.Report report = new G2CurrentMultimodalRefusalEvaluator(
                judge.client(), "test-judge"
        ).evaluate(input.profile(), input.runtimePlan(), input.resultsByCaseId(), input.answersByCaseId());

        assertThat(report.datasetId()).isEqualTo("g2-current-multimodal-v1");
        assertThat(report.noAnswerCaseCount()).isEqualTo(1);
        assertThat(report.evaluatedCaseCount()).isEqualTo(1);
        assertThat(report.skippedCaseCount()).isZero();
        assertThat(report.correctAbstentionCount()).isEqualTo(1);
        assertThat(report.abstentionAccuracy()).isEqualTo(1D);
        assertThat(report.judgeCallCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("complete");
        assertThat(report.qualityGateStatus()).isEqualTo("not_configured");
        assertThat(report.candidateOnly()).isTrue();
        assertThat(report.releaseEligible()).isFalse();
        verify(judge.client(), times(1)).prompt();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(judge.requestSpec(), times(1)).user(prompt.capture());
        G2CurrentMultimodalCandidateFixtureLoader.CandidateCase noAnswer = input.profile().cases().stream()
                .filter(G2CurrentMultimodalCandidateFixtureLoader.CandidateCase::shouldAbstain)
                .findFirst()
                .orElseThrow();
        assertThat(prompt.getValue()).contains(noAnswer.query(), input.answersByCaseId().get(noAnswer.caseId()));
    }

    @Test
    void recordsDisabledAndInvalidJudgeAsSkipped() throws Exception {
        EvaluationInput input = evaluationInput();

        G2CurrentMultimodalRefusalEvaluator.Report disabled = new G2CurrentMultimodalRefusalEvaluator(null, null)
                .evaluate(input.profile(), input.runtimePlan(), input.resultsByCaseId(), input.answersByCaseId());
        assertThat(disabled.status()).isEqualTo("disabled");
        assertThat(disabled.judgeCallCount()).isZero();
        assertThat(disabled.skipReasons()).containsEntry("judge_disabled", 1);

        G2CurrentMultimodalRefusalEvaluator.Report invalid = new G2CurrentMultimodalRefusalEvaluator(
                judgeClient("{\"correctAbstention\":\"yes\"}").client(), "test-judge"
        ).evaluate(input.profile(), input.runtimePlan(), input.resultsByCaseId(), input.answersByCaseId());
        assertThat(invalid.status()).isEqualTo("skipped");
        assertThat(invalid.evaluatedCaseCount()).isZero();
        assertThat(invalid.skipReasons()).containsEntry("invalid_judge_response", 1);
    }

    @Test
    void requiresExactCaseCoverageAndCandidateRuntimePlan() throws Exception {
        EvaluationInput input = evaluationInput();

        assertThatThrownBy(() -> new G2CurrentMultimodalRefusalEvaluator(null, null)
                .evaluate(input.profile(), input.runtimePlan(), Map.of(), input.answersByCaseId()))
                .isInstanceOf(IllegalStateException.class);

        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan nonCandidatePlan =
                new G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan(
                        input.runtimePlan().assetImportPlan(), false, input.runtimePlan().chunks()
                );
        assertThatThrownBy(() -> new G2CurrentMultimodalRefusalEvaluator(null, null)
                .evaluate(input.profile(), nonCandidatePlan, input.resultsByCaseId(), input.answersByCaseId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate");
    }

    private GeneratorFixture judgeClient(String response) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(response);
        return new GeneratorFixture(client, requestSpec);
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
        Map<String, List<RagRetrievalResult>> resultsByCaseId = new LinkedHashMap<>();
        Map<String, String> answersByCaseId = new LinkedHashMap<>();
        G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset tableAsset = profile.assets().get(0);
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (candidateCase.shouldAbstain()) {
                RagRetrievalResult result = new RagRetrievalResult();
                result.setChunkId(tableAsset.runtimeChunkUuid());
                result.setKbId(profile.assetImportPlan().knowledgeBaseId());
                result.setContent(tableAsset.content());
                resultsByCaseId.put(candidateCase.caseId(), List.of(result));
                answersByCaseId.put(candidateCase.caseId(), "证据不足，无法确认。");
                continue;
            }
            RagRetrievalResult result = new RagRetrievalResult();
            result.setChunkId(candidateCase.goldChunkLogicalId().equals(tableAsset.logicalChunkId())
                    ? tableAsset.runtimeChunkUuid()
                    : profile.assets().stream()
                    .filter(asset -> asset.logicalChunkId().equals(candidateCase.goldChunkLogicalId()))
                    .map(G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset::runtimeChunkUuid)
                    .findFirst()
                    .orElseThrow());
            result.setKbId(profile.assetImportPlan().knowledgeBaseId());
            result.setContent(candidateCase.goldEvidence());
            resultsByCaseId.put(candidateCase.caseId(), List.of(result));
            answersByCaseId.put(candidateCase.caseId(), "Answer with citation.");
        }
        return new EvaluationInput(profile, runtimePlan, resultsByCaseId, answersByCaseId);
    }

    private record GeneratorFixture(ChatClient client, ChatClient.ChatClientRequestSpec requestSpec) {
    }

    private record EvaluationInput(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId,
            Map<String, String> answersByCaseId
    ) {
    }
}
