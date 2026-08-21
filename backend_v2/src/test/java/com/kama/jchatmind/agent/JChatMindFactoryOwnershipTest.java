package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JChatMindFactoryOwnershipTest {

    @Test
    void shouldRejectForeignAgentEvenWhenKnowledgeBaseBindingIsEmpty() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter agentConverter = mock(AgentConverter.class);
        Agent agent = Agent.builder().id("agent-1").userId("8").model("deepseek-chat").build();
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(agentConverter.toDTO(agent)).thenReturn(agentConfig(List.of()));
        JChatMindFactory factory = factory(
                agentMapper,
                agentConverter,
                mock(KnowledgeBaseConverter.class),
                mock(KnowledgeBaseAccessService.class),
                mock(AgentKnowledgeBaseBindingService.class)
        );

        assertThatThrownBy(() -> factory.create("7", "agent-1", "session-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问 Agent");
    }

    @Test
    void shouldFilterInaccessibleKnowledgeBasesFromHistoricalAgentBinding() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter agentConverter = mock(AgentConverter.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseConverter knowledgeBaseConverter = mock(KnowledgeBaseConverter.class);
        Agent agent = Agent.builder().id("agent-1").userId("7").model("deepseek-chat").build();
        KnowledgeBase foreignKnowledgeBase = KnowledgeBase.builder().id("foreign-kb").ownerId("8").build();
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(agentConverter.toDTO(agent)).thenReturn(agentConfig(List.of("foreign-kb")));
        when(knowledgeBaseMapper.selectById("foreign-kb")).thenReturn(foreignKnowledgeBase);
        when(knowledgeBaseConverter.toDTO(foreignKnowledgeBase))
                .thenReturn(KnowledgeBaseDTO.builder().id("foreign-kb").build());
        JChatMindFactory factory = factory(
                agentMapper,
                agentConverter,
                knowledgeBaseConverter,
                new KnowledgeBaseAccessService(knowledgeBaseMapper),
                mock(AgentKnowledgeBaseBindingService.class)
        );

        JChatMind runtime = factory.create("7", "agent-1", "session-1");

        assertThat(availableKbs(runtime)).isEmpty();
    }

    @Test
    void shouldExcludeDeletedKnowledgeBaseFromHistoricalAgentBinding() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter agentConverter = mock(AgentConverter.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        Agent agent = Agent.builder().id("agent-1").userId("7").model("deepseek-chat").build();
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(agentConverter.toDTO(agent)).thenReturn(agentConfig(List.of("deleted-kb")));
        when(knowledgeBaseMapper.selectById("deleted-kb")).thenReturn(null);
        JChatMindFactory factory = factory(
                agentMapper,
                agentConverter,
                mock(KnowledgeBaseConverter.class),
                new KnowledgeBaseAccessService(knowledgeBaseMapper),
                mock(AgentKnowledgeBaseBindingService.class)
        );

        JChatMind runtime = factory.create("7", "agent-1", "session-1");

        assertThat(availableKbs(runtime)).isEmpty();
    }

    @Test
    void shouldResolveRuntimeKnowledgeBasesFromPersistentBindings() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter agentConverter = mock(AgentConverter.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseConverter knowledgeBaseConverter = mock(KnowledgeBaseConverter.class);
        AgentKnowledgeBaseBindingService bindingService = mock(AgentKnowledgeBaseBindingService.class);
        Agent agent = Agent.builder().id("agent-1").userId("7").model("deepseek-chat").build();
        KnowledgeBase ownedKnowledgeBase = KnowledgeBase.builder().id("owned-kb").ownerId("7").build();
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(agentConverter.toDTO(agent)).thenReturn(agentConfig(List.of("legacy-kb")));
        when(bindingService.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("owned-kb"));
        when(knowledgeBaseMapper.selectById("owned-kb")).thenReturn(ownedKnowledgeBase);
        when(knowledgeBaseConverter.toDTO(ownedKnowledgeBase))
                .thenReturn(KnowledgeBaseDTO.builder().id("owned-kb").build());
        JChatMindFactory factory = factory(
                agentMapper,
                agentConverter,
                knowledgeBaseConverter,
                new KnowledgeBaseAccessService(knowledgeBaseMapper),
                bindingService
        );

        JChatMind runtime = factory.create("7", "agent-1", "session-1");

        assertThat(availableKbs(runtime))
                .extracting(item -> ((KnowledgeBaseDTO) item).getId())
                .containsExactly("owned-kb");
    }

    private JChatMindFactory factory(
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseConverter knowledgeBaseConverter,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService
    ) {
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently(anyString(), anyInt(), anyString()))
                .thenReturn(List.of());
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        when(userMemoryFacadeService.getConfirmedMemories(anyString())).thenReturn(List.of());
        ToolFacadeService toolFacadeService = mock(ToolFacadeService.class);
        when(toolFacadeService.getFixedTools()).thenReturn(List.of());
        ToolCallbackProvider externalToolCallbackProvider = mock(ToolCallbackProvider.class);
        when(externalToolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(Map.of("deepseek-chat", mock(ChatClient.class)));

        return new JChatMindFactory(
                chatClientRegistry,
                mock(SseService.class),
                agentMapper,
                agentConverter,
                knowledgeBaseConverter,
                knowledgeBaseAccessService,
                agentKnowledgeBaseBindingService,
                toolFacadeService,
                chatMessageFacadeService,
                mock(ChatMessageConverter.class),
                userMemoryFacadeService,
                externalToolCallbackProvider,
                mock(HarnessRunner.class),
                mock(HarnessInterceptorChain.class)
        );
    }

    private List<?> availableKbs(JChatMind runtime) throws Exception {
        Field field = JChatMind.class.getDeclaredField("availableKbs");
        field.setAccessible(true);
        return (List<?>) field.get(runtime);
    }

    private AgentDTO agentConfig(List<String> allowedKbs) {
        return AgentDTO.builder()
                .allowedTools(List.of())
                .allowedKbs(allowedKbs)
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .build();
    }
}
