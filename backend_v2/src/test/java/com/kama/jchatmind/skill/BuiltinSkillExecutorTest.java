package com.kama.jchatmind.skill;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinSkillExecutorTest {

    @Test
    void shouldReturnAnAbstentionAndInterruptTheToolWhenTheSkillBudgetExpires() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        BuiltinSkillExecutor executor = new BuiltinSkillExecutor(
                registryWithBudget(1, 2),
                (userId, sessionId, question, knowledgeBaseIds) -> {
                    try {
                        Thread.sleep(1_500L);
                        return SkillKnowledgeToolResult.executed(List.of(retrievalResult("chunk-1", "kb-1")));
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        return SkillKnowledgeToolResult.blocked("interrupted");
                    }
                }
        );

        SkillExecutionResult result = execute(executor);

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.output()).containsEntry("abstained", true);
        assertThat(result.output().get("reason").toString()).contains("超时");
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldRejectAnInvocationWhenItsConcurrencyBudgetIsFull() throws Exception {
        CountDownLatch firstInvocationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstInvocation = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();
        BuiltinSkillExecutor executor = new BuiltinSkillExecutor(
                registryWithBudget(3, 1),
                (userId, sessionId, question, knowledgeBaseIds) -> {
                    invocationCount.incrementAndGet();
                    firstInvocationStarted.countDown();
                    try {
                        releaseFirstInvocation.await();
                        return SkillKnowledgeToolResult.executed(List.of(retrievalResult("chunk-1", "kb-1")));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return SkillKnowledgeToolResult.blocked("interrupted");
                    }
                }
        );
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Thread releaseTimer = new Thread(() -> {
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            releaseFirstInvocation.countDown();
        });
        releaseTimer.setDaemon(true);
        releaseTimer.start();

        try {
            var firstInvocation = caller.submit(() -> execute(executor));
            assertThat(firstInvocationStarted.await(1, TimeUnit.SECONDS)).isTrue();

            SkillExecutionResult secondResult = execute(executor);

            assertThat(secondResult.validation().valid()).isTrue();
            assertThat(secondResult.output()).containsEntry("abstained", true);
            assertThat(secondResult.output().get("reason").toString()).contains("并发");
            assertThat(invocationCount).hasValue(1);
            releaseFirstInvocation.countDown();
            assertThat(firstInvocation.get(1, TimeUnit.SECONDS).validation().valid()).isTrue();
        } finally {
            releaseFirstInvocation.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void shouldExecuteTechnicalDecisionComparisonOnlyWithinPreparedKnowledgeBaseScope() {
        AtomicReference<List<String>> requestedKnowledgeBaseIds = new AtomicReference<>();
        AtomicReference<String> requestedQuestion = new AtomicReference<>();
        BuiltinSkillExecutor executor = new BuiltinSkillExecutor(
                new BuiltinSkillRegistry(),
                (userId, sessionId, question, knowledgeBaseIds) -> {
                    requestedQuestion.set(question);
                    requestedKnowledgeBaseIds.set(knowledgeBaseIds);
                    return SkillKnowledgeToolResult.executed(List.of(retrievalResult("chunk-1", "kb-2")));
                }
        );

        SkillExecutionResult result = executor.execute(
                "user-1",
                "session-1",
                "technical-decision-comparison",
                Map.of(
                        "question", "比较当前两种缓存方案的取舍",
                        "kbIds", List.of("kb-2")
                ),
                List.of("kb-1", "kb-2")
        );

        assertThat(requestedQuestion.get()).isEqualTo("比较当前两种缓存方案的取舍");
        assertThat(requestedKnowledgeBaseIds.get()).containsExactly("kb-2");
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.output()).containsEntry("conclusion", "已检索到支持技术方案对比的 1 条授权知识库证据。");
        assertThat(result.output()).containsEntry("evidence", List.of(Map.of("chunkId", "chunk-1", "kbId", "kb-2")));
    }

    @Test
    void shouldReturnAnExplicitAbstentionWhenHarnessPreventsKnowledgeToolExecution() {
        BuiltinSkillExecutor executor = new BuiltinSkillExecutor(
                new BuiltinSkillRegistry(),
                (userId, sessionId, question, knowledgeBaseIds) -> SkillKnowledgeToolResult.blocked("工具执行被 Harness 拒绝")
        );

        SkillExecutionResult result = executor.execute(
                "user-1",
                "session-1",
                "technical-decision-comparison",
                Map.of("question", "比较两种方案"),
                List.of("kb-1")
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.output()).containsEntry("abstained", true);
        assertThat(result.output()).containsEntry("reason", "工具执行被 Harness 拒绝");
        assertThat(result.output()).doesNotContainKey("evidence");
    }

    private RagRetrievalResult retrievalResult(String chunkId, String knowledgeBaseId) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setKbId(knowledgeBaseId);
        return result;
    }

    private SkillExecutionResult execute(BuiltinSkillExecutor executor) {
        return executor.execute(
                "user-1",
                "session-1",
                "technical-decision-comparison",
                Map.of("question", "比较两种方案"),
                List.of("kb-1")
        );
    }

    private BuiltinSkillRegistry registryWithBudget(int timeoutSeconds, int concurrencyLimit) {
        return new BuiltinSkillRegistry() {
            @Override
            public PreparedSkillInvocation prepare(
                    String skillId,
                    Map<String, Object> input,
                    List<String> authorizedKnowledgeBaseIds
            ) {
                PreparedSkillInvocation prepared = super.prepare(skillId, input, authorizedKnowledgeBaseIds);
                SkillDefinition original = prepared.definition();
                SkillDefinition testDefinition = new SkillDefinition(
                        original.id(), original.version(), original.name(), original.inputFields(),
                        original.allowedTools(), original.executionMode(), timeoutSeconds,
                        concurrencyLimit, original.requiresApproval()
                );
                return new PreparedSkillInvocation(
                        testDefinition, prepared.input(), prepared.knowledgeBaseIds()
                );
            }
        };
    }
}
