package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.intent.IntentDecisionSource;
import com.kama.jchatmind.intent.IntentRoutingProperties;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JChatMindFactoryToolScopeTest {

    @Test
    void shouldNotInjectExternalCallbackWithoutExplicitAgentAuthorization() throws Exception {
        JChatMind runtime = createRuntime(List.of());

        assertThat(availableTools(runtime))
                .extracting(callback -> callback.getToolDefinition().name())
                .isEmpty();
    }

    @Test
    void shouldInjectOnlyExplicitlyAuthorizedExternalCallback() throws Exception {
        JChatMind runtime = createRuntime(List.of("externalLookup"));

        assertThat(availableTools(runtime))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("externalLookup");
    }

    @Test
    void shouldUseConfiguredIntentFallbackOnlyWhenEnabled() throws Exception {
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setLlmEnabled(true);

        JChatMind runtime = factory(List.of(), properties).create("user-1", "agent-1", "session-1");

        assertThat(toolSelectionPolicy(runtime).decide("帮我处理一下这个").decisionSource())
                .isEqualTo(IntentDecisionSource.LLM_FALLBACK);
    }

    private JChatMind createRuntime(List<String> allowedTools) throws Exception {
        return factory(allowedTools).create("user-1", "agent-1", "session-1");
    }

    private JChatMindFactory factory(List<String> allowedTools) throws Exception {
        return factory(allowedTools, new IntentRoutingProperties());
    }

    private JChatMindFactory factory(
            List<String> allowedTools,
            IntentRoutingProperties intentRoutingProperties
    ) throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter agentConverter = mock(AgentConverter.class);
        Agent agent = Agent.builder().id("agent-1").userId("user-1").model("deepseek-chat").build();
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(agentConverter.toDTO(agent)).thenReturn(AgentDTO.builder()
                .allowedTools(allowedTools)
                .allowedKbs(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .build());

        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.getChatMessagesBySessionIdRecently(anyString(), anyInt(), anyString()))
                .thenReturn(List.of());
        UserMemoryFacadeService memoryService = mock(UserMemoryFacadeService.class);
        when(memoryService.getConfirmedMemories(anyString())).thenReturn(List.of());
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of());
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of());

        ToolCallbackProvider externalProvider = () -> new ToolCallback[]{callback("externalLookup")};
        ChatClient chatClient = mock(ChatClient.class);
        if (intentRoutingProperties.isLlmEnabled()) {
            ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.system(anyString())).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(responseSpec);
            when(responseSpec.content()).thenReturn(
                    "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.6}"
            );
        }

        return new JChatMindFactory(
                new ChatClientRegistry(Map.of("deepseek-chat", chatClient)),
                mock(SseService.class),
                agentMapper,
                agentConverter,
                mock(KnowledgeBaseConverter.class),
                mock(KnowledgeBaseAccessService.class),
                mock(AgentKnowledgeBaseBindingService.class),
                toolFacadeService,
                messageService,
                mock(ChatMessageConverter.class),
                memoryService,
                externalProvider,
                mock(HarnessRunner.class),
                mock(HarnessInterceptorChain.class),
                intentRoutingProperties
        );
    }

    private ToolSelectionPolicy toolSelectionPolicy(JChatMind runtime) throws Exception {
        Field field = JChatMind.class.getDeclaredField("toolSelectionPolicy");
        field.setAccessible(true);
        return (ToolSelectionPolicy) field.get(runtime);
    }

    @SuppressWarnings("unchecked")
    private List<ToolCallback> availableTools(JChatMind runtime) throws Exception {
        Field field = JChatMind.class.getDeclaredField("availableTools");
        field.setAccessible(true);
        return (List<ToolCallback>) field.get(runtime);
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
