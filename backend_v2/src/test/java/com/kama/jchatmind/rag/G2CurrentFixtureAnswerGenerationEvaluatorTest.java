package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
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

class G2CurrentFixtureAnswerGenerationEvaluatorTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void generatesAnswersForApprovedCandidateUsingCandidateQueries() throws Exception {
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate = approvedCandidate();
        Map<String, String> contexts = contexts(candidate);
        RecordingGeneratorFixture generator = recordingGenerator("候选回答");

        G2CurrentFixtureAnswerGenerationEvaluator.Report report =
                new G2CurrentFixtureAnswerGenerationEvaluator(generator.client(), "test-generator")
                        .generate(candidate, contexts);

        assertThat(report.datasetId()).isEqualTo(candidate.datasetId());
        assertThat(G2CurrentFixtureAnswerGenerationEvaluator.SYSTEM_PROMPT_VERSION)
                .isEqualTo("citation-minimal-v3");
        assertThat(G2CurrentFixtureAnswerGenerationEvaluator.SYSTEM_PROMPT_SHA256)
                .matches("[0-9a-f]{64}");
        assertThat(report.caseCount()).isEqualTo(20);
        assertThat(report.generatedCaseCount()).isEqualTo(20);
        assertThat(report.finalAnswersByCaseId()).containsOnlyKeys(
                candidate.cases().stream()
                        .map(G2CurrentFixtureCitationCandidateLoader.CandidateCase::caseId)
                        .toArray(String[]::new)
        );
        assertThat(generator.systemPrompts()).hasSize(candidate.cases().size())
                .allSatisfy(systemPrompt -> assertThat(systemPrompt)
                        .contains(
                                "Answer the exact question and every requested part",
                                "For a general question, state the general rule only",
                                "do not add conditional exceptions or edge cases unless the query explicitly asks for them"
                        ));
        for (int index = 0; index < candidate.cases().size(); index++) {
            G2CurrentFixtureCitationCandidateLoader.CandidateCase item = candidate.cases().get(index);
            String prompt = generator.prompts().get(index);
            assertThat(prompt).contains(item.query(), contexts.get(item.caseId()));
            assertThat(item.goldChunkIds()).allSatisfy(goldId -> assertThat(prompt).doesNotContain(goldId));
        }
    }

    @Test
    void rejectsPendingCandidateBeforeCallingGenerator() throws Exception {
        G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate = pendingCandidate();
        assertThatThrownBy(() -> new G2CurrentFixtureAnswerGenerationEvaluator(null, "test-generator")
                .generate(candidate, contexts(candidate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("全部通过 review");
    }

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
        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        verify(generator.client(), times(profile.caseCount())).prompt();
        verify(generator.requestSpec(), times(profile.caseCount())).system(systemPrompts.capture());
        verify(generator.requestSpec(), times(profile.caseCount())).user(prompts.capture());
        assertThat(systemPrompts.getAllValues()).allSatisfy(systemPrompt ->
                assertThat(systemPrompt.replaceAll("\\s+", " ")).contains(
                        "Answer the exact question and every requested part",
                        "omit related details the question did not ask for",
                        "For a general question, state the general rule only",
                        "do not add conditional exceptions or edge cases unless the query explicitly asks for them",
                        "Every factual claim must have a direct supporting citation",
                        "Use the minimum number of citation markers needed",
                        "cite each supporting chunk at most once",
                        "abstain without a citation marker"
                )
        );
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

    private Map<String, String> contexts(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate
    ) {
        Map<String, String> contexts = new LinkedHashMap<>();
        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase item : candidate.cases()) {
            contexts.put(item.caseId(), "Reviewed candidate context\n引用: "
                    + "11111111-1111-1111-1111-111111111111");
        }
        return contexts;
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

    private RecordingGeneratorFixture recordingGenerator(String answer) {
        List<String> prompts = new ArrayList<>();
        List<String> systemPrompts = new ArrayList<>();
        ChatClient.CallResponseSpec responseSpec = (ChatClient.CallResponseSpec) Proxy.newProxyInstance(
                ChatClient.CallResponseSpec.class.getClassLoader(),
                new Class<?>[]{ChatClient.CallResponseSpec.class},
                (proxy, method, args) -> method.getName().equals("content") ? answer
                        : throwUnsupported(method.getName())
        );
        ChatClient.ChatClientRequestSpec requestSpec = (ChatClient.ChatClientRequestSpec) Proxy.newProxyInstance(
                ChatClient.ChatClientRequestSpec.class.getClassLoader(),
                new Class<?>[]{ChatClient.ChatClientRequestSpec.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "system" -> {
                        systemPrompts.add((String) args[0]);
                        yield proxy;
                    }
                    case "user" -> {
                        prompts.add((String) args[0]);
                        yield proxy;
                    }
                    case "call" -> responseSpec;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        ChatClient client = (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class},
                (proxy, method, args) -> method.getName().equals("prompt") ? requestSpec
                        : throwUnsupported(method.getName())
        );
        return new RecordingGeneratorFixture(client, prompts, systemPrompts);
    }

    private Object throwUnsupported(String methodName) {
        throw new UnsupportedOperationException(methodName);
    }

    private record GeneratorFixture(
            ChatClient client,
            ChatClient.ChatClientRequestSpec requestSpec
    ) {
    }

    private record RecordingGeneratorFixture(
            ChatClient client,
            List<String> prompts,
            List<String> systemPrompts
    ) {
    }
}
