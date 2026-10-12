package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.intent.BusinessIntent;
import com.kama.jchatmind.intent.IntentDecisionMetadata;
import com.kama.jchatmind.intent.IntentDecisionEvent;
import com.kama.jchatmind.intent.IntentFallbackRouter;
import com.kama.jchatmind.intent.IntentPolicyEngine;
import com.kama.jchatmind.intent.IntentRoutingProperties;
import com.kama.jchatmind.intent.IntentRoute;
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

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindIntentAuditTest {

    @Test
    void shouldPassIntentDecisionMetadataAlongsideToolAttempt() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        CreateChatMessageResponse savedMessage = mock(CreateChatMessageResponse.class);
        HarnessRunner harnessRunner = mock(HarnessRunner.class);
        List<IntentDecisionEvent> telemetryEvents = new ArrayList<>();
        HarnessResult rejectedResult = new HarnessResult();
        rejectedResult.setDecision("call-1", HarnessDecision.rejected("test rejection"));

        IntentFallbackRouter fallbackRouter = new IntentFallbackRouter(
                new IntentRouter(),
                new ObjectMapper(),
                query -> "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.6}",
                new IntentRoutingProperties(),
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
                Flux.just(chatResponseWithToolCall()),
                Flux.just(chatResponse("已完成"))
        );
        when(messageService.createChatMessage(any(ChatMessageDTO.class), eq("user-1")))
                .thenReturn(savedMessage);
        when(savedMessage.getChatMessageId()).thenReturn("assistant-message");
        when(harnessRunner.beforeExecution(
                eq("user-1"),
                eq("agent-1"),
                eq("session-1"),
                anyInt(),
                anyList(),
                anySet(),
                anySet(),
                any(IntentDecisionMetadata.class)
        )).thenReturn(rejectedResult);

        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                10,
                List.of(new UserMessage("帮我处理一下这个")),
                List.of(callback("KnowledgeTool")),
                List.of(),
                "session-1",
                mock(SseService.class),
                messageService,
                mock(ChatMessageConverter.class),
                harnessRunner,
                toolSelectionPolicy
        );

        agent.run();

        ArgumentCaptor<IntentDecisionMetadata> metadataCaptor = ArgumentCaptor.forClass(IntentDecisionMetadata.class);
        verify(harnessRunner).beforeExecution(
                eq("user-1"),
                eq("agent-1"),
                eq("session-1"),
                anyInt(),
                anyList(),
                anySet(),
                anySet(),
                metadataCaptor.capture()
        );
        IntentDecisionMetadata metadata = metadataCaptor.getValue();
        IntentDecisionEvent telemetry = telemetryEvents.get(0);
        assertThat(metadata.traceId()).isNotBlank();
        assertThat(metadata.correlationId()).isNotBlank();
        assertThat(metadata.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(metadata.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(metadata.confidenceMargin()).isNull();
        assertThat(metadata.candidateCapabilities()).isEmpty();
        assertThat(metadata.selectedToolNames()).isEmpty();
        assertThat(telemetry.traceId()).isEqualTo(metadata.traceId());
        assertThat(telemetry.correlationId()).isEqualTo(metadata.correlationId());
        assertThat(telemetry.candidateIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
        assertThat(telemetry.candidateMargin()).isEqualTo(0.6D);
        assertThat(telemetry.minMargin()).isEqualTo(0.15D);
    }

    private ChatResponse chatResponseWithToolCall() {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "KnowledgeTool", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(output)));
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
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
