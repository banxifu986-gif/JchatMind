package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class G2CurrentFixtureAnswerGenerationEvaluatorTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void generatesOneContextBoundAnswerForEveryFrozenCase() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        Map<String, String> contexts = contexts(profile);
        GeneratorFixture generator = generator("回答基于证据。\n引用: 11111111-1111-1111-1111-111111111111");

        G2CurrentFixtureAnswerGenerationEvaluator.Report report =
                new G2CurrentFixtureAnswerGenerationEvaluator(generator.client(), "test-generator")
                        .generate(profile, contexts);

        assertThat(report.datasetId()).isEqualTo("fixture-fast-v1");
        assertThat(report.status()).isEqualTo("complete");
        assertThat(report.caseCount()).isEqualTo(profile.caseCount());
        assertThat(report.generatedCaseCount()).isEqualTo(profile.caseCount());
        assertThat(report.failedCaseCount()).isZero();
        assertThat(report.skippedCaseCount()).isZero();
        assertThat(report.generationCallCount()).isEqualTo(profile.caseCount());
        assertThat(report.finalAnswersByCaseId()).containsOnlyKeys(
                profile.cases().stream().map(G2CurrentFixtureCase::caseId).toArray(String[]::new)
        );
        assertThat(report.finalAnswersByCaseId().values()).containsOnly(
                "回答基于证据。\n引用: 11111111-1111-1111-1111-111111111111"
        );

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(generator.client(), times(profile.caseCount())).prompt();
        verify(generator.requestSpec(), times(profile.caseCount())).user(prompts.capture());
        for (int index = 0; index < profile.cases().size(); index++) {
            G2CurrentFixtureCase item = profile.cases().get(index);
            String prompt = prompts.getAllValues().get(index);
            assertThat(prompt)
                    .contains(item.query(), contexts.get(item.caseId()));
            assertThat(item.goldChunkIds()).allSatisfy(goldId ->
                    assertThat(prompt).doesNotContain(goldId));
        }
    }

    @Test
    void disabledGeneratorProducesCompleteEmptyAnswerCoverageWithoutCalls() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);

        G2CurrentFixtureAnswerGenerationEvaluator.Report report =
                new G2CurrentFixtureAnswerGenerationEvaluator(null, null)
                        .generate(profile, contexts(profile));

        assertThat(report.status()).isEqualTo("disabled");
        assertThat(report.caseCount()).isEqualTo(profile.caseCount());
        assertThat(report.generatedCaseCount()).isZero();
        assertThat(report.failedCaseCount()).isZero();
        assertThat(report.skippedCaseCount()).isEqualTo(profile.caseCount());
        assertThat(report.generationCallCount()).isZero();
        assertThat(report.reasonCounts()).containsEntry("generator_disabled", profile.caseCount());
        assertThat(report.finalAnswersByCaseId()).containsOnlyKeys(
                profile.cases().stream().map(G2CurrentFixtureCase::caseId).toArray(String[]::new)
        );
        assertThat(report.finalAnswersByCaseId().values()).containsOnly("");
    }

    @Test
    void recordsGenerationFailuresWithoutExposingExceptionMessages() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        GeneratorFixture generator = generator("unused");
        when(generator.requestSpec().call()).thenThrow(new IllegalStateException("sensitive prompt content"));

        G2CurrentFixtureAnswerGenerationEvaluator.Report report =
                new G2CurrentFixtureAnswerGenerationEvaluator(generator.client(), "test-generator")
                        .generate(profile, contexts(profile));

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.generatedCaseCount()).isZero();
        assertThat(report.failedCaseCount()).isEqualTo(profile.caseCount());
        assertThat(report.skippedCaseCount()).isZero();
        assertThat(report.generationCallCount()).isEqualTo(profile.caseCount());
        assertThat(report.reasonCounts()).containsEntry("generation_error", profile.caseCount());
        assertThat(report.finalAnswersByCaseId().values()).containsOnly("");
        assertThat(report.toString()).doesNotContain("sensitive prompt content");
    }

    private Map<String, String> contexts(G2CurrentFixtureProfile profile) {
        Map<String, String> contexts = new LinkedHashMap<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            contexts.put(item.caseId(), "Retrieved context for " + item.caseId()
                    + "\n引用: 11111111-1111-1111-1111-111111111111");
        }
        return contexts;
    }

    private GeneratorFixture generator(String answer) {
        ChatClient generator = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(generator.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(answer);
        return new GeneratorFixture(generator, requestSpec);
    }

    private record GeneratorFixture(
            ChatClient client,
            ChatClient.ChatClientRequestSpec requestSpec
    ) {
    }
}
