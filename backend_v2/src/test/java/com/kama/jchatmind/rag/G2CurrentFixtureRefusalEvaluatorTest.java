package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;
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

class G2CurrentFixtureRefusalEvaluatorTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void judgesOnlyGoldNoAnswerCasesAndReportsRefusalAccuracy() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        ChatClient judgeClient = judgeClient("{\"correctAbstention\":true}");

        G2CurrentFixtureRefusalEvaluator.Report report = new G2CurrentFixtureRefusalEvaluator(
                judgeClient, "test-judge"
        ).evaluate(profile, contexts(profile), answers(profile));

        assertThat(report.noAnswerCaseCount()).isEqualTo(2);
        assertThat(report.evaluatedCaseCount()).isEqualTo(2);
        assertThat(report.skippedCaseCount()).isZero();
        assertThat(report.correctAbstentionCount()).isEqualTo(2);
        assertThat(report.abstentionAccuracy()).isEqualTo(1D);
        assertThat(report.judgeCallCount()).isEqualTo(2);
        assertThat(report.status()).isEqualTo("complete");
        assertThat(report.qualityGateStatus()).isEqualTo("not_configured");
        verify(judgeClient, times(2)).prompt();
    }

    @Test
    void disabledOrInvalidJudgeResponseIsReportedAsSkipped() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        Map<String, String> contexts = contexts(profile);
        Map<String, String> answers = answers(profile);

        G2CurrentFixtureRefusalEvaluator.Report disabled = new G2CurrentFixtureRefusalEvaluator(null, null)
                .evaluate(profile, contexts, answers);
        assertThat(disabled.status()).isEqualTo("disabled");
        assertThat(disabled.judgeCallCount()).isZero();
        assertThat(disabled.skipReasons()).containsEntry("judge_disabled", 2);

        G2CurrentFixtureRefusalEvaluator.Report invalid = new G2CurrentFixtureRefusalEvaluator(
                judgeClient("{\"correctAbstention\":\"yes\"}"), "test-judge"
        ).evaluate(profile, contexts, answers);
        assertThat(invalid.status()).isEqualTo("skipped");
        assertThat(invalid.evaluatedCaseCount()).isZero();
        assertThat(invalid.skipReasons()).containsEntry("invalid_judge_response", 2);
    }

    @Test
    void requiresExactCaseCoverage() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);

        assertThatThrownBy(() -> new G2CurrentFixtureRefusalEvaluator(null, null)
                .evaluate(profile, Map.of(), answers(profile)))
                .isInstanceOf(IllegalStateException.class);
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
