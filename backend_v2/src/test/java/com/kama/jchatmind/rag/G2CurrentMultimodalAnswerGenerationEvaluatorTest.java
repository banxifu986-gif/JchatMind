package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
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

class G2CurrentMultimodalAnswerGenerationEvaluatorTest {

    private static final String MANIFEST_PATH =
            "rag-eval/datasets/manifests/g2-current-multimodal-v1.json";

    @Test
    void generatesOneAnswerPerCandidateCaseFromQueryAndRetrievedContextOnly() throws Exception {
        EvaluationInput input = evaluationInput();
        GeneratorFixture generator = generator("根据已检索资料回答。\n引用: 11111111-1111-1111-1111-111111111111");

        G2CurrentMultimodalAnswerGenerationEvaluator.Report report =
                new G2CurrentMultimodalAnswerGenerationEvaluator(generator.client(), "test-generator")
                        .generate(input.profile(), input.runtimePlan(), input.resultsByCaseId());

        assertThat(report.datasetId()).isEqualTo("g2-current-multimodal-v1");
        assertThat(report.status()).isEqualTo("complete");
        assertThat(report.caseCount()).isEqualTo(4);
        assertThat(report.generatedCaseCount()).isEqualTo(4);
        assertThat(report.failedCaseCount()).isZero();
        assertThat(report.skippedCaseCount()).isZero();
        assertThat(report.generationCallCount()).isEqualTo(4);
        assertThat(report.candidateOnly()).isTrue();
        assertThat(report.releaseEligible()).isFalse();
        assertThat(report.finalAnswersByCaseId()).containsOnlyKeys(
                input.profile().cases().stream()
                        .map(G2CurrentMultimodalCandidateFixtureLoader.CandidateCase::caseId)
                        .toArray(String[]::new)
        );

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        verify(generator.client(), times(4)).prompt();
        verify(generator.requestSpec(), times(4)).system(systemPrompts.capture());
        verify(generator.requestSpec(), times(4)).user(prompts.capture());
        assertThat(systemPrompts.getAllValues()).allSatisfy(systemPrompt -> {
            assertThat(systemPrompt).contains("directly answers the question");
            assertThat(systemPrompt).contains("minimum number of sources");
            assertThat(systemPrompt).contains("do not cite anything");
            assertThat(systemPrompt).contains("explicitly names an asset type");
        });
        for (int index = 0; index < input.profile().cases().size(); index++) {
            G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase =
                    input.profile().cases().get(index);
            String prompt = prompts.getAllValues().get(index);
            assertThat(prompt).contains(candidateCase.query());
            assertThat(prompt).doesNotContain("goldAsset", "goldChunkLogicalId", "goldEvidence");
            List<RagRetrievalResult> results = input.resultsByCaseId().get(candidateCase.caseId());
            if (results.isEmpty()) {
                assertThat(prompt).contains("Retrieved context:");
            } else {
                assertThat(prompt).contains(results.get(0).getChunkId(), results.get(0).getContent());
                assertThat(prompt).contains(candidateCase.goldAsset().assetType());
            }
        }
    }

    @Test
    void disabledGeneratorReturnsCompleteEmptyAnswerCoverageWithoutCalls() throws Exception {
        EvaluationInput input = evaluationInput();

        G2CurrentMultimodalAnswerGenerationEvaluator.Report report =
                new G2CurrentMultimodalAnswerGenerationEvaluator(null, null)
                        .generate(input.profile(), input.runtimePlan(), input.resultsByCaseId());

        assertThat(report.status()).isEqualTo("disabled");
        assertThat(report.generatedCaseCount()).isZero();
        assertThat(report.failedCaseCount()).isZero();
        assertThat(report.skippedCaseCount()).isEqualTo(4);
        assertThat(report.generationCallCount()).isZero();
        assertThat(report.reasonCounts()).containsEntry("generator_disabled", 4);
        assertThat(report.finalAnswersByCaseId().values()).containsOnly("");
    }

    @Test
    void recordsEmptyResponsesAsFailures() throws Exception {
        EvaluationInput input = evaluationInput();
        GeneratorFixture generator = generator(" ");

        G2CurrentMultimodalAnswerGenerationEvaluator.Report report =
                new G2CurrentMultimodalAnswerGenerationEvaluator(generator.client(), "test-generator")
                        .generate(input.profile(), input.runtimePlan(), input.resultsByCaseId());

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.generatedCaseCount()).isZero();
        assertThat(report.failedCaseCount()).isEqualTo(4);
        assertThat(report.reasonCounts()).containsEntry("empty_answer", 4);
        assertThat(report.finalAnswersByCaseId().values()).containsOnly("");
    }

    @Test
    void recordsGenerationErrorsWithoutExposingExceptionMessages() throws Exception {
        EvaluationInput input = evaluationInput();
        GeneratorFixture generator = generator("unused");
        String sensitiveMessage = "sensitive upstream response message";
        String sensitiveBody = "sensitive upstream response body";
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED,
                sensitiveMessage,
                HttpHeaders.EMPTY,
                sensitiveBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        when(generator.requestSpec().call()).thenThrow(unauthorized);

        G2CurrentMultimodalAnswerGenerationEvaluator.Report report =
                new G2CurrentMultimodalAnswerGenerationEvaluator(generator.client(), "test-generator")
                        .generate(input.profile(), input.runtimePlan(), input.resultsByCaseId());

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.generatedCaseCount()).isZero();
        assertThat(report.failedCaseCount()).isEqualTo(4);
        assertThat(report.reasonCounts()).containsEntry("generation_error", 4);
        assertThat(report.finalAnswersByCaseId().values()).containsOnly("");
        assertThat(report.cases()).allSatisfy(caseResult -> {
            assertThat(caseResult.failureType()).contains("HttpClientErrorException");
            assertThat(caseResult.httpStatus()).isEqualTo(401);
        });
        assertThat(report.toString()).doesNotContain(sensitiveMessage, sensitiveBody);
    }

    @Test
    void requiresExactCaseCoverageAndCandidateRuntimePlan() throws Exception {
        EvaluationInput input = evaluationInput();

        assertThatThrownBy(() -> new G2CurrentMultimodalAnswerGenerationEvaluator(null, null)
                .generate(input.profile(), input.runtimePlan(), Map.of()))
                .isInstanceOf(IllegalStateException.class);

        G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan nonCandidatePlan =
                new G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan(
                        input.runtimePlan().assetImportPlan(), false, input.runtimePlan().chunks()
                );
        assertThatThrownBy(() -> new G2CurrentMultimodalAnswerGenerationEvaluator(null, null)
                .generate(input.profile(), nonCandidatePlan, input.resultsByCaseId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate");
    }

    private GeneratorFixture generator(String answer) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(answer);
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
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (candidateCase.shouldAbstain()) {
                resultsByCaseId.put(candidateCase.caseId(), List.of());
                continue;
            }
            G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk = runtimePlan.chunks().stream()
                    .filter(chunk -> chunk.chunk().logicalChunkId().equals(candidateCase.goldChunkLogicalId()))
                    .findFirst()
                    .orElseThrow();
            RagRetrievalResult result = new RagRetrievalResult();
            result.setChunkId(runtimeChunk.chunk().chunkId());
            result.setKbId(profile.assetImportPlan().knowledgeBaseId());
            result.setContent("Synthetic retrieved context for " + candidateCase.caseId());
            resultsByCaseId.put(candidateCase.caseId(), List.of(result));
        }
        return new EvaluationInput(profile, runtimePlan, resultsByCaseId);
    }

    private record GeneratorFixture(
            ChatClient client,
            ChatClient.ChatClientRequestSpec requestSpec
    ) {
    }

    private record EvaluationInput(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
    }
}
