package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.converter.ChatMessageConverter;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindToolGuardTest {

    @Test
    void shouldPassOnlyIntentMatchedToolsToTheModel() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        SseService sseService = mock(SseService.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        ChatMessageConverter messageConverter = mock(ChatMessageConverter.class);
        CreateChatMessageResponse savedMessage = mock(CreateChatMessageResponse.class);

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponse("答复")));
        when(messageService.createChatMessage(any(ChatMessageDTO.class), eq("user-1")))
                .thenReturn(savedMessage);
        when(savedMessage.getChatMessageId()).thenReturn("assistant-message-1");

        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                10,
                List.of(new UserMessage("根据项目文档，如何发布服务？")),
                List.of(callback("KnowledgeTool"), callback("sendEmail"), callback("databaseQuery")),
                List.of(),
                "session-1",
                sseService,
                messageService,
                messageConverter,
                mock(HarnessRunner.class)
        );

        agent.run();

        ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(requestSpec).toolCallbacks(callbacks.capture());
        assertThat(callbacks.getValue())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldUsePreviousUserQueryToRouteContextualFollowUp() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        SseService sseService = mock(SseService.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        ChatMessageConverter messageConverter = mock(ChatMessageConverter.class);
        CreateChatMessageResponse savedMessage = mock(CreateChatMessageResponse.class);

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponse("继续说明")));
        when(messageService.createChatMessage(any(ChatMessageDTO.class), eq("user-1")))
                .thenReturn(savedMessage);
        when(savedMessage.getChatMessageId()).thenReturn("assistant-message-follow-up");

        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                10,
                List.of(
                        new UserMessage("请查询项目的发布规范"),
                        new AssistantMessage("发布规范要求先完成验证。"),
                        new UserMessage("为什么？")
                ),
                List.of(callback("KnowledgeTool"), callback("sendEmail")),
                List.of(),
                "session-follow-up",
                sseService,
                messageService,
                messageConverter,
                mock(HarnessRunner.class)
        );

        agent.run();

        ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(requestSpec).toolCallbacks(callbacks.capture());
        assertThat(callbacks.getValue())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
    }

    @Test
    void shouldUseInjectedFallbackPolicyForAmbiguousRequest() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        SseService sseService = mock(SseService.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        ChatMessageConverter messageConverter = mock(ChatMessageConverter.class);
        CreateChatMessageResponse savedMessage = mock(CreateChatMessageResponse.class);

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponse("答复")));
        when(messageService.createChatMessage(any(ChatMessageDTO.class), eq("user-1")))
                .thenReturn(savedMessage);
        when(savedMessage.getChatMessageId()).thenReturn("assistant-message-2");

        IntentFallbackRouter fallbackRouter = new IntentFallbackRouter(
                new IntentRouter(),
                new ObjectMapper(),
                query -> "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.6}",
                new IntentRoutingProperties(),
                false,
                null
        );
        ToolSelectionPolicy injectedPolicy = new ToolSelectionPolicy(
                fallbackRouter,
                new IntentPolicyEngine()
        );

        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                10,
                List.of(new UserMessage("帮我处理一下这个")),
                List.of(callback("KnowledgeTool"), callback("sendEmail")),
                List.of(),
                "session-1",
                sseService,
                messageService,
                messageConverter,
                mock(HarnessRunner.class),
                injectedPolicy
        );

        agent.run();

        ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(requestSpec).toolCallbacks(callbacks.capture());
        assertThat(callbacks.getValue())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("KnowledgeTool");
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
