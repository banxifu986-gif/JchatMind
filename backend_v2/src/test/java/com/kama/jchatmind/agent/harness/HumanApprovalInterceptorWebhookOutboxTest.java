package com.kama.jchatmind.agent.harness;

import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor;
import com.kama.jchatmind.webhook.WebhookApprovalOutboxService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HumanApprovalInterceptorWebhookOutboxTest {

    @Test
    void shouldCreateApprovalOutboxOnlyForTheExplicitValidatedSourceContext() {
        HarnessProperties properties = new HarnessProperties();
        properties.getHumanApproval().setEnabled(true);
        properties.getHumanApproval().setTools(List.of("sendEmail"));
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        WebhookApprovalOutboxService webhookOutboxService = mock(WebhookApprovalOutboxService.class);
        HumanApprovalInterceptor interceptor = new HumanApprovalInterceptor(
                properties,
                approvalStore,
                webhookOutboxService
        );
        HarnessContext context = HarnessContext.builder()
                .sessionId("session-1")
                .agentId("agent-1")
                .userId("7")
                .webhookSourceId("source-7")
                .toolCallId("call-1")
                .toolName("sendEmail")
                .toolInput("private tool input")
                .build();
        HarnessResult result = new HarnessResult();
        result.addContext(context);

        interceptor.beforeExecution(context, result);

        String approvalId = result.getDecision("call-1").getApprovalRequestId();
        verify(webhookOutboxService).enqueue("source-7", 7L, approvalId, "session-1");
        assertThat(approvalStore.getRequest(approvalId)).isPresent();
    }
}
