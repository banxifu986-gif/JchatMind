package com.kama.jchatmind.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPlanVerifierTest {

    private final WorkflowPlanVerifier verifier = new WorkflowPlanVerifier();

    @Test
    void shouldAcceptAnAuthorizedPlanWhoseClaimHasMatchingEvidence() {
        WorkflowVerificationResult result = verifier.verify(
                new WorkflowPlan(
                        2,
                        30,
                        List.of(new WorkflowStep(
                                "retrieve-cache-decision",
                                "KnowledgeTool",
                                "cache-decision",
                                "当前服务使用 Redis 作为缓存层",
                                List.of(new WorkflowEvidence(
                                        "chunk-cache-1",
                                        "cache-decision",
                                        "架构文档明确 Redis 是缓存层"
                                ))
                        ))
                ),
                List.of("KnowledgeTool")
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void shouldRejectAClaimWithoutEvidence() {
        WorkflowVerificationResult result = verifier.verify(
                new WorkflowPlan(
                        1,
                        30,
                        List.of(new WorkflowStep(
                                "retrieve-cache-decision",
                                "KnowledgeTool",
                                "cache-decision",
                                "当前服务使用 Redis 作为缓存层",
                                List.of()
                        ))
                ),
                List.of("KnowledgeTool")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("步骤 retrieve-cache-decision 缺少证据");
    }

    @Test
    void shouldRejectAPlanThatRequestsAnUnauthorizedTool() {
        WorkflowVerificationResult result = verifier.verify(
                new WorkflowPlan(
                        1,
                        30,
                        List.of(new WorkflowStep(
                                "send-email",
                                "EmailTool",
                                "notification",
                                "向团队发送通知",
                                List.of(new WorkflowEvidence("chunk-1", "notification", "通知要求"))
                        ))
                ),
                List.of("KnowledgeTool")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("步骤 send-email 请求了未授权工具：EmailTool");
    }

    @Test
    void shouldRejectContradictoryClaimsForTheSameFact() {
        WorkflowVerificationResult result = verifier.verify(
                new WorkflowPlan(
                        2,
                        30,
                        List.of(
                                new WorkflowStep(
                                        "read-design-a",
                                        "KnowledgeTool",
                                        "release-mode",
                                        "发布模式是蓝绿发布",
                                        List.of(new WorkflowEvidence("chunk-a", "release-mode", "采用蓝绿发布"))
                                ),
                                new WorkflowStep(
                                        "read-design-b",
                                        "KnowledgeTool",
                                        "release-mode",
                                        "发布模式是滚动发布",
                                        List.of(new WorkflowEvidence("chunk-b", "release-mode", "采用滚动发布"))
                                )
                        )
                ),
                List.of("KnowledgeTool")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("事实 release-mode 存在矛盾声明");
    }

    @Test
    void shouldRejectAPlanThatExceedsExistingAgentStepOrTimeoutBudget() {
        WorkflowVerificationResult result = verifier.verify(
                new WorkflowPlan(21, 31, List.of()),
                List.of("KnowledgeTool")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains(
                "工作流步骤预算不能超过 20",
                "工作流超时预算不能超过 30 秒"
        );
    }

    @Test
    void shouldRejectAPlanWhoseActualStepCountExceedsItsDeclaredBudget() {
        WorkflowStep firstStep = new WorkflowStep(
                "read-a",
                "KnowledgeTool",
                "fact-a",
                "事实 A",
                List.of(new WorkflowEvidence("chunk-a", "fact-a", "证据 A"))
        );
        WorkflowStep secondStep = new WorkflowStep(
                "read-b",
                "KnowledgeTool",
                "fact-b",
                "事实 B",
                List.of(new WorkflowEvidence("chunk-b", "fact-b", "证据 B"))
        );

        WorkflowVerificationResult result = verifier.verify(
                new WorkflowPlan(1, 30, List.of(firstStep, secondStep)),
                List.of("KnowledgeTool")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("工作流实际步骤数超出预算");
    }
}
