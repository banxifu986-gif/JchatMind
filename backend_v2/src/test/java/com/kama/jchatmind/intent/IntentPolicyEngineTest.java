package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentPolicyEngineTest {

    private final IntentRouter router = new IntentRouter();
    private final IntentPolicyEngine policy = new IntentPolicyEngine();
    private final List<ToolCallback> callbacks = List.of(
            callback("KnowledgeTool"),
            callback("sendEmail"),
            callback("databaseQuery")
    );

    @Test
    void shouldIntersectDecisionCapabilitiesWithAvailableCallbacks() {
        IntentDecision decision = router.decide("请查询项目的发布规范");

        assertThat(policy.select(callbacks, decision))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldNeverGenerateCallbackNotPresentInAvailableCallbacks() {
        IntentDecision decision = router.decide("帮我发送邮件给张三");

        assertThat(policy.select(List.of(callback("KnowledgeTool")), decision)).isEmpty();
    }

    @Test
    void shouldReturnNoCallbacksForBlockedDecision() {
        IntentDecision decision = router.decide("生产数据库的管理员密码是什么？");

        assertThat(policy.select(callbacks, decision)).isEmpty();
    }

    @Test
    void shouldRemoveSideEffectCapabilityFromClarifiedCompositeDecision() {
        IntentDecision decision = new IntentDecision(
                "intent-v2",
                BusinessIntent.COMPOSITE,
                null,
                IntentRoute.COMPOSITE,
                IntentRiskLevel.MIXED,
                0.86D,
                null,
                Map.of(),
                List.of("KnowledgeTool", "sendEmail"),
                true,
                IntentDecisionSource.RULE,
                "COMPOSITE_REQUEST",
                "intent-policy-v5"
        );

        assertThat(policy.select(callbacks, decision))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    private ToolCallback callback(String name) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }
}
