package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class G2CurrentFixtureAnswerEvaluationPipelineTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void evaluatesBothRuntimeArmsThroughGenerationCitationQualityAndRefusal() throws Exception {
        PipelineInput input = pipelineInput();
        ClientFixture generator = generator(input);
        ClientFixture judge = judge();

        G2CurrentFixtureAnswerEvaluationPipeline.Report report =
                new G2CurrentFixtureAnswerEvaluationPipeline().evaluate(
                        input.profile(),
                        input.fixture(),
                        input.plan(),
                        input.retrievedByCaseId(),
                        input.retrievedByCaseId(),
                        generator.client(),
                        "deepseek-chat",
                        judge.client(),
                        "glm-4.6"
                );

        assertThat(report.answerGenerationEnabled()).isTrue();
        assertThat(report.answerQualityJudgeEnabled()).isTrue();
        assertThat(report.answerCitationAccuracyMeasured()).isTrue();
        assertThat(report.answerQualityMeasured()).isTrue();
        assertThat(report.answerRefusalMeasured()).isTrue();
        assertThat(report.fixedGeneration().status()).isEqualTo("complete");
        assertThat(report.routerGeneration().status()).isEqualTo("complete");
        assertThat(report.fixedCitation().gate().verdict()).isEqualTo("eligible_for_answer_citation_ab");
        assertThat(report.routerCitation().gate().verdict()).isEqualTo("eligible_for_answer_citation_ab");
        assertThat(report.fixedQuality().evaluatedCaseCount()).isEqualTo(input.profile().answerableCaseCount());
        assertThat(report.routerQuality().evaluatedCaseCount()).isEqualTo(input.profile().answerableCaseCount());
        assertThat(report.pairedQuality().pairedCaseCount()).isEqualTo(input.profile().answerableCaseCount());
        assertThat(report.pairedQuality().gateStatus()).isEqualTo("not_configured");
        assertThat(report.fixedRefusal().evaluatedCaseCount()).isEqualTo(input.profile().abstentionCaseCount());
        assertThat(report.routerRefusal().evaluatedCaseCount()).isEqualTo(input.profile().abstentionCaseCount());
        assertThat(report.fixedRefusal().correctAbstentionCount()).isEqualTo(input.profile().abstentionCaseCount());
        assertThat(report.routerRefusal().correctAbstentionCount()).isEqualTo(input.profile().abstentionCaseCount());
        assertThat(report.pairedRefusal().pairedCaseCount()).isEqualTo(input.profile().abstentionCaseCount());
        assertThat(report.pairedRefusal().abstentionAccuracyDelta()).isZero();
        assertThat(report.pairedRefusal().gateStatus()).isEqualTo("not_configured");
        verify(generator.requestSpec(), times(input.profile().caseCount() * 2)).call();
        verify(judge.requestSpec(), times(input.profile().answerableCaseCount() * 2
                + input.profile().abstentionCaseCount() * 2)).call();
    }

    @Test
    void leavesEveryAnswerAndJudgeDisabledWithoutChatClients() throws Exception {
        PipelineInput input = pipelineInput();

        G2CurrentFixtureAnswerEvaluationPipeline.Report report =
                new G2CurrentFixtureAnswerEvaluationPipeline().evaluate(
                        input.profile(),
                        input.fixture(),
                        input.plan(),
                        input.retrievedByCaseId(),
                        input.retrievedByCaseId(),
                        null,
                        null,
                        null,
                        null
                );

        assertThat(report.answerGenerationEnabled()).isFalse();
        assertThat(report.answerQualityJudgeEnabled()).isFalse();
        assertThat(report.answerCitationAccuracyMeasured()).isFalse();
        assertThat(report.answerQualityMeasured()).isFalse();
        assertThat(report.answerRefusalMeasured()).isFalse();
        assertThat(report.fixedGeneration().status()).isEqualTo("disabled");
        assertThat(report.fixedQuality().status()).isEqualTo("disabled");
        assertThat(report.fixedRefusal().status()).isEqualTo("disabled");
        assertThat(report.pairedQuality()).isNull();
        assertThat(report.pairedRefusal()).isNull();
    }

    @Test
    void doesNotMarkQualityMeasuredWhenOnlyPartOfTheAnswerableCasesArePaired() throws Exception {
        PipelineInput input = pipelineInput();
        ClientFixture generator = generator(input);
        ClientFixture judge = partialQualityJudge();

        G2CurrentFixtureAnswerEvaluationPipeline.Report report =
                new G2CurrentFixtureAnswerEvaluationPipeline().evaluate(
                        input.profile(),
                        input.fixture(),
                        input.plan(),
                        input.retrievedByCaseId(),
                        input.retrievedByCaseId(),
                        generator.client(),
                        "deepseek-chat",
                        judge.client(),
                        "glm-4.6"
                );

        assertThat(report.pairedQuality().status()).isEqualTo("partial");
        assertThat(report.pairedQuality().pairedCaseCount()).isEqualTo(input.profile().answerableCaseCount() - 1);
        assertThat(report.pairedQuality().unpairedCaseCount()).isEqualTo(1);
        assertThat(report.answerQualityMeasured()).isFalse();
        assertThat(report.answerRefusalMeasured()).isTrue();
    }

    @Test
    void doesNotMarkRefusalMeasuredWhenOnlyOneNoAnswerCaseIsPaired() throws Exception {
        PipelineInput input = pipelineInput();
        ClientFixture generator = generator(input);
        ClientFixture judge = partialRefusalJudge();

        G2CurrentFixtureAnswerEvaluationPipeline.Report report =
                new G2CurrentFixtureAnswerEvaluationPipeline().evaluate(
                        input.profile(),
                        input.fixture(),
                        input.plan(),
                        input.retrievedByCaseId(),
                        input.retrievedByCaseId(),
                        generator.client(),
                        "deepseek-chat",
                        judge.client(),
                        "glm-4.6"
                );

        assertThat(report.answerQualityMeasured()).isTrue();
        assertThat(report.pairedRefusal().status()).isEqualTo("partial");
        assertThat(report.pairedRefusal().pairedCaseCount()).isEqualTo(1);
        assertThat(report.pairedRefusal().unpairedCaseCount()).isEqualTo(1);
        assertThat(report.answerRefusalMeasured()).isFalse();
    }

    @Test
    void refusesAQualityJudgeWithoutAnAnswerGenerator() throws Exception {
        PipelineInput input = pipelineInput();
        ClientFixture judge = judge();

        assertThatThrownBy(() -> new G2CurrentFixtureAnswerEvaluationPipeline().evaluate(
                input.profile(), input.fixture(), input.plan(), input.retrievedByCaseId(),
                input.retrievedByCaseId(), null, null, judge.client(), "glm-4.6"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer generator");
    }

    private PipelineInput pipelineInput() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
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
        G2CurrentFixtureRuntimeImportPlan.Prepared plan =
                G2CurrentFixtureRuntimeImportPlan.prepare(fixture, candidates);
        Map<String, String> runtimeIdByLogicalId = new LinkedHashMap<>();
        plan.logicalChunkIdByRuntimeUuid().forEach((runtimeId, logicalId) ->
                runtimeIdByLogicalId.put(logicalId, runtimeId)
        );
        String unrelatedRuntimeId = runtimeIdByLogicalId.values().iterator().next();
        Map<String, List<RagRetrievalResult>> retrievedByCaseId = new LinkedHashMap<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            List<String> logicalChunkIds = item.shouldAbstain()
                    ? List.of()
                    : item.goldChunkIds();
            if (logicalChunkIds.isEmpty()) {
                retrievedByCaseId.put(item.caseId(), List.of(result(unrelatedRuntimeId, "Unrelated evidence")));
            } else {
                retrievedByCaseId.put(item.caseId(), logicalChunkIds.stream()
                        .map(logicalId -> result(runtimeIdByLogicalId.get(logicalId), "Evidence for " + logicalId))
                        .toList());
            }
        }
        return new PipelineInput(profile, fixture, plan, retrievedByCaseId, runtimeIdByLogicalId);
    }

    private ClientFixture generator(PipelineInput input) {
        List<String> answers = new ArrayList<>();
        for (int arm = 0; arm < 2; arm++) {
            for (G2CurrentFixtureCase item : input.profile().cases()) {
                if (item.shouldAbstain()) {
                    answers.add("证据不足，无法确认。");
                } else {
                    answers.add(item.goldChunkIds().stream()
                            .map(input.runtimeIdByLogicalId()::get)
                            .map(runtimeId -> "引用: " + runtimeId)
                            .reduce("回答。", (left, right) -> left + "\n" + right));
                }
            }
        }
        ClientFixture fixture = clientFixture();
        Deque<String> responseQueue = new ArrayDeque<>(answers);
        when(fixture.responseSpec().content()).thenAnswer(invocation -> responseQueue.removeFirst());
        return fixture;
    }

    private ClientFixture judge() {
        ClientFixture fixture = clientFixture();
        AtomicReference<String> systemPrompt = new AtomicReference<>("");
        when(fixture.requestSpec().system(anyString())).thenAnswer(invocation -> {
            systemPrompt.set(invocation.getArgument(0));
            return fixture.requestSpec();
        });
        when(fixture.responseSpec().content()).thenAnswer(invocation ->
                systemPrompt.get().contains("correctAbstention")
                        ? "{\"correctAbstention\":true}"
                        : "{\"faithfulness\":0.9,\"answerRelevancy\":0.8}"
        );
        return fixture;
    }

    private ClientFixture partialQualityJudge() {
        ClientFixture fixture = clientFixture();
        AtomicReference<String> systemPrompt = new AtomicReference<>("");
        AtomicInteger qualityCalls = new AtomicInteger();
        when(fixture.requestSpec().system(anyString())).thenAnswer(invocation -> {
            systemPrompt.set(invocation.getArgument(0));
            return fixture.requestSpec();
        });
        when(fixture.responseSpec().content()).thenAnswer(invocation -> {
            if (systemPrompt.get().contains("correctAbstention")) {
                return "{\"correctAbstention\":true}";
            }
            return qualityCalls.getAndIncrement() == 0
                    ? "{\"faithfulness\":\"invalid\",\"answerRelevancy\":0.9}"
                    : "{\"faithfulness\":0.9,\"answerRelevancy\":0.8}";
        });
        return fixture;
    }

    private ClientFixture partialRefusalJudge() {
        ClientFixture fixture = clientFixture();
        AtomicReference<String> systemPrompt = new AtomicReference<>("");
        AtomicInteger refusalCalls = new AtomicInteger();
        when(fixture.requestSpec().system(anyString())).thenAnswer(invocation -> {
            systemPrompt.set(invocation.getArgument(0));
            return fixture.requestSpec();
        });
        when(fixture.responseSpec().content()).thenAnswer(invocation -> {
            if (systemPrompt.get().contains("correctAbstention")) {
                return refusalCalls.getAndIncrement() == 0
                        ? "{\"correctAbstention\":\"unknown\"}"
                        : "{\"correctAbstention\":true}";
            }
            return "{\"faithfulness\":0.9,\"answerRelevancy\":0.8}";
        });
        return fixture;
    }

    private ClientFixture clientFixture() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("");
        return new ClientFixture(client, requestSpec, responseSpec);
    }

    private RagRetrievalResult result(String chunkId, String content) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setContent(content);
        return result;
    }

    private record PipelineInput(
            G2CurrentFixtureProfile profile,
            G2CurrentFixtureRuntimeCorpus fixture,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            Map<String, List<RagRetrievalResult>> retrievedByCaseId,
            Map<String, String> runtimeIdByLogicalId
    ) {
    }

    private record ClientFixture(
            ChatClient client,
            ChatClient.ChatClientRequestSpec requestSpec,
            ChatClient.CallResponseSpec responseSpec
    ) {
    }
}
