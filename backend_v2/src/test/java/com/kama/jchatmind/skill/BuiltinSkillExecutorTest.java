package com.kama.jchatmind.skill;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinSkillExecutorTest {

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
}
