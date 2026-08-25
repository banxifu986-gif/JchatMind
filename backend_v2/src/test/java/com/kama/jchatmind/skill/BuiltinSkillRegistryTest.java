package com.kama.jchatmind.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltinSkillRegistryTest {

    private final BuiltinSkillRegistry registry = new BuiltinSkillRegistry();

    @Test
    void shouldPrepareTechnicalDecisionComparisonWithinAuthorizedKnowledgeBases() {
        PreparedSkillInvocation invocation = registry.prepare(
                "technical-decision-comparison",
                Map.of(
                        "question", "比较当前两种缓存方案的取舍",
                        "kbIds", List.of("kb-2", "kb-1", "kb-2")
                ),
                List.of("kb-1", "kb-2", "kb-3")
        );

        assertThat(invocation.definition().id()).isEqualTo("technical-decision-comparison");
        assertThat(invocation.definition().version()).isEqualTo("v1");
        assertThat(invocation.definition().allowedTools()).containsExactly("KnowledgeTool");
        assertThat(invocation.definition().requiresApproval()).isFalse();
        assertThat(invocation.knowledgeBaseIds()).containsExactly("kb-2", "kb-1");
        assertThat(invocation.input()).containsEntry("question", "比较当前两种缓存方案的取舍");
    }

    @Test
    void shouldRejectSkillInputThatRequestsAnUndeclaredTool() {
        assertThatThrownBy(() -> registry.prepare(
                "technical-decision-comparison",
                Map.of(
                        "question", "比较两种方案",
                        "tools", List.of("EmailTool")
                ),
                List.of("kb-1")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许指定工具");
    }

    @Test
    void shouldRejectKnowledgeBasesOutsideCallerScope() {
        assertThatThrownBy(() -> registry.prepare(
                "technical-decision-comparison",
                Map.of(
                        "question", "比较两种方案",
                        "kbIds", List.of("kb-unauthorized")
                ),
                List.of("kb-1")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("授权范围");
    }

    @Test
    void shouldRejectStructuredOutputWithoutEvidenceOrAbstentionReason() {
        PreparedSkillInvocation invocation = registry.prepare(
                "technical-decision-comparison",
                Map.of("question", "比较两种方案"),
                List.of("kb-1")
        );

        SkillOutputValidationResult result = registry.validateOutput(
                invocation,
                Map.of("conclusion", "方案 A 更合适")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("回答必须提供至少一条知识库证据，或明确拒答原因");
    }
}
