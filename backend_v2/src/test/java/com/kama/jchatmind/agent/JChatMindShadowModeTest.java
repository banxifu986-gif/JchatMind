package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.intent.IntentDecisionEvent;
import com.kama.jchatmind.intent.IntentFallbackRouter;
import com.kama.jchatmind.intent.IntentPolicyEngine;
import com.kama.jchatmind.intent.IntentRoutingProperties;
import com.kama.jchatmind.intent.IntentRouter;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindShadowModeTest {

    @Test
    void shouldNeverExecuteToolProposedOnlyByShadowCandidate() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        CreateChatMessageResponse savedMessage = mock(CreateChatMessageResponse.class);
        AtomicInteger toolCalls = new AtomicInteger();
        List<IntentDecisionEvent> telemetryEvents = new ArrayList<>();
        HarnessProperties harnessProperties = new HarnessProperties();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(harnessProperties);
        HarnessRunner harnessRunner = new HarnessRunner(
                harnessProperties,
                new HarnessInterceptorChain(List.of()),
                new InMemoryApprovalStore(),
                auditStore
        );
        IntentRoutingProperties routingProperties = new IntentRoutingProperties();
        IntentFallbackRouter fallbackRouter = new IntentFallbackRouter(
                new IntentRouter(),
                new ObjectMapper(),
                query -> "{\"businessIntent\":\"SEND_EMAIL\",\"confidence\":0.99,\"margin\":0.9}",
                routingProperties,
                true,
                telemetryEvents::add
        );
        ToolSelectionPolicy toolSelectionPolicy = new ToolSelectionPolicy(
                fallbackRouter,
                new IntentPolicyEngine()
        );

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(
                Flux.just(chatResponseWithEmailToolCall()),
                Flux.just(chatResponse("没有执行邮件发送操作。"))
        );
        when(messageService.createChatMessage(any(ChatMessageDTO.class), eq("user-1")))
                .thenReturn(savedMessage);
        when(savedMessage.getChatMessageId()).thenReturn("shadow-message");

        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                10,
                List.of(new UserMessage("帮我处理一下这个")),
                List.of(callback("sendEmail", toolCalls)),
                List.of(),
                "session-shadow",
                mock(SseService.class),
                messageService,
                mock(ChatMessageConverter.class),
                harnessRunner,
                toolSelectionPolicy
        );

        agent.run();

        assertThat(toolCalls).hasValue(0);
        assertThat(telemetryEvents).hasSize(2);
        assertThat(telemetryEvents.get(0).traceId()).isEqualTo(telemetryEvents.get(1).traceId());
        assertThat(telemetryEvents.get(0).correlationId())
                .isNotEqualTo(telemetryEvents.get(1).correlationId());
        telemetryEvents.forEach(event -> {
            assertThat(event.ruleIntent()).isEqualTo(com.kama.jchatmind.intent.BusinessIntent.CLARIFY);
            assertThat(event.candidateIntent()).isEqualTo(com.kama.jchatmind.intent.BusinessIntent.CLARIFY);
            assertThat(event.candidateCapabilities()).isEmpty();
        });
        ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(requestSpec, times(2)).toolCallbacks(callbacks.capture());
        assertThat(callbacks.getAllValues()).allSatisfy(turnCallbacks -> assertThat(turnCallbacks).isEmpty());
    }

    private ChatResponse chatResponseWithEmailToolCall() {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-email", "function", "sendEmail", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(output)));
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ToolCallback callback(String name, AtomicInteger calls) {
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
                calls.incrementAndGet();
                return "sent";
            }
        };
    }
}
