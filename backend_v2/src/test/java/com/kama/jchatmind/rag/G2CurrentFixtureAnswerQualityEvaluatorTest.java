package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.assertj.core.data.Offset;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class G2CurrentFixtureAnswerQualityEvaluatorTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void measuresOnlyAnswerableCasesAndDoesNotUseGoldInJudgePrompt() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        ChatClient judgeClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(judgeClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"faithfulness\":0.8,\"answerRelevancy\":0.75}");
        Map<String, String> contexts = contexts(profile);
        Map<String, String> answers = answers(profile);

        G2CurrentFixtureAnswerQualityReport report =
                new G2CurrentFixtureAnswerQualityEvaluator(judgeClient, "test-judge")
                        .evaluate(profile, contexts, answers);

        assertThat(report.datasetId()).isEqualTo("fixture-fast-v1");
        assertThat(report.caseCount()).isEqualTo(20);
        assertThat(report.answerableCaseCount()).isEqualTo(18);
        assertThat(report.noAnswerCaseCount()).isEqualTo(2);
        assertThat(report.evaluatedCaseCount()).isEqualTo(18);
        assertThat(report.skippedCaseCount()).isEqualTo(2);
        assertThat(report.judgeCallCount()).isEqualTo(18);
        assertThat(report.meanFaithfulness()).isCloseTo(0.8D, Offset.offset(1.0e-12D));
        assertThat(report.meanAnswerRelevancy()).isCloseTo(0.75D, Offset.offset(1.0e-12D));
        assertThat(report.status()).isEqualTo("complete");
        assertThat(report.answerQualityMeasured()).isTrue();
        assertThat(report.qualityGateStatus()).isEqualTo("not_configured");
        assertThat(report.skipReasons()).containsEntry("no_answer_case", 2);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(18)).user(prompt.capture());
        String goldChunkId = profile.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .flatMap(item -> item.goldChunkIds().stream())
                .findFirst()
                .orElseThrow();
        assertThat(prompt.getAllValues()).allSatisfy(value -> assertThat(value).doesNotContain(goldChunkId));
    }

    @Test
    void disabledJudgeSkipsWithoutInvokingChatClient() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);

        G2CurrentFixtureAnswerQualityReport report =
                new G2CurrentFixtureAnswerQualityEvaluator(null, null)
                        .evaluate(profile, contexts(profile), answers(profile));

        assertThat(report.status()).isEqualTo("disabled");
        assertThat(report.judgeCallCount()).isZero();
        assertThat(report.evaluatedCaseCount()).isZero();
        assertThat(report.skipReasons()).containsEntry("judge_disabled", 18)
                .containsEntry("no_answer_case", 2);
    }

    @Test
    void rejectsIncompleteAnswerOrContextCoverageAndSkipsInvalidJudgeScores() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        Map<String, String> contexts = contexts(profile);
        Map<String, String> answers = answers(profile);

        assertThatThrownBy(() -> new G2CurrentFixtureAnswerQualityEvaluator(null, null)
                .evaluate(profile, Map.of(), answers))
                .isInstanceOf(IllegalStateException.class);

        G2CurrentFixtureAnswerQualityReport report =
                new G2CurrentFixtureAnswerQualityEvaluator(
                        judgeClient("{\"faithfulness\":1.2,\"answerRelevancy\":0.75}"), "test-judge"
                ).evaluate(profile, contexts, answers);

        assertThat(report.status()).isEqualTo("skipped");
        assertThat(report.evaluatedCaseCount()).isZero();
        assertThat(report.judgeCallCount()).isEqualTo(18);
        assertThat(report.skipReasons()).containsEntry("invalid_judge_response", 18);
    }

    private Map<String, String> contexts(G2CurrentFixtureProfile profile) {
        Map<String, String> contexts = new LinkedHashMap<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            contexts.put(item.caseId(), "Retrieved context for " + item.caseId());
        }
        return contexts;
    }

    private Map<String, String> answers(G2CurrentFixtureProfile profile) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            answers.put(item.caseId(), item.shouldAbstain()
                    ? "证据不足，无法确认。"
                    : "Generated answer for " + item.caseId());
        }
        return answers;
    }

    private ChatClient judgeClient(String response) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(response);
        return chatClient;
    }
}
