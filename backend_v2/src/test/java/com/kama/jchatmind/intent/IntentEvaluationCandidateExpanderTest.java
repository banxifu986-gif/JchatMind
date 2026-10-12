package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationCandidateExpanderTest {

    @Test
    void shouldPreserveCompositeQueryWhenItRequiresClarification() {
        IntentEvaluationCase composite = new IntentEvaluationCase(
                "composite-seed",
                "intent-candidate-v1",
                "dev",
                "先查发布规范，然后通知项目负责人",
                "请查询项目的发布规范",
                null,
                BusinessIntent.COMPOSITE,
                IntentRoute.COMPOSITE,
                IntentRiskLevel.MIXED,
                List.of("KnowledgeTool"),
                true,
                false,
                List.of("composite"),
                "pending_review"
        );

        List<IntentEvaluationCase> expanded = IntentEvaluationCandidateExpander.expand(
                List.of(composite),
                "intent-candidate-v1"
        );

        assertThat(expanded).hasSize(10);
        assertThat(expanded).allSatisfy(item -> {
            assertThat(item.query()).contains("发布规范", "然后", "通知项目负责人");
            assertThat(item.previousUserQuery()).isEqualTo("请查询项目的发布规范");
            assertThat(item.expectedBusinessIntent()).isEqualTo(BusinessIntent.COMPOSITE);
        });
    }
}
