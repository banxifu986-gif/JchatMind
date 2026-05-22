package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.proxy.HarnessToolCallbackProxy;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final UserMemoryFacadeService userMemoryFacadeService;
    private final List<ToolCallbackProvider> toolCallbackProviders;
    private final HarnessRunner harnessRunner;
    private final HarnessInterceptorChain harnessInterceptorChain;

    private AgentDTO agentConfig;

    public JChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            UserMemoryFacadeService userMemoryFacadeService,
            List<ToolCallbackProvider> toolCallbackProviders,
            HarnessRunner harnessRunner,
            HarnessInterceptorChain harnessInterceptorChain
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.userMemoryFacadeService = userMemoryFacadeService;
        this.toolCallbackProviders = toolCallbackProviders;
        this.harnessRunner = harnessRunner;
        this.harnessInterceptorChain = harnessInterceptorChain;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    private List<Message> loadMemory(String userId, String chatSessionId) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<Message> memory = new ArrayList<>();
        memory.addAll(loadLongTermMemory(userId));

        List<ChatMessageDTO> chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                userId,
                chatSessionId,
                messageLength
        );
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM -> {
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(new SystemMessage(chatMessageDTO.getContent()));
                    }
                }
                case USER -> {
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(new UserMessage(chatMessageDTO.getContent()));
                    }
                }
                case ASSISTANT -> memory.add(AssistantMessage.builder()
                        .content(chatMessageDTO.getContent())
                        .toolCalls(chatMessageDTO.getMetadata() != null
                                ? chatMessageDTO.getMetadata().getToolCalls()
                                : null)
                        .build());
                case TOOL -> {
                    if (chatMessageDTO.getMetadata() != null && chatMessageDTO.getMetadata().getToolResponse() != null) {
                        memory.add(ToolResponseMessage.builder()
                                .responses(List.of(chatMessageDTO.getMetadata().getToolResponse()))
                                .build());
                    }
                }
                default -> {
                    log.error("Unsupported message role: {}", chatMessageDTO.getRole().getRole());
                    throw new IllegalStateException("Unsupported message role");
                }
            }
        }
        return memory;
    }

    private List<Message> loadLongTermMemory(String userId) {
        List<UserMemory> memories = userMemoryFacadeService.getConfirmedMemories(userId);
        if (memories.isEmpty()) {
            return Collections.emptyList();
        }

        String content = memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + memory.getContent())
                .collect(Collectors.joining("\n"));
        return List.of(new SystemMessage("以下是用户已确认的长期记忆，请在后续回答中遵守和利用：\n" + content));
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            agentConfig = agentConverter.toDTO(agent);
            return agentConfig;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                kbDTOs.add(knowledgeBaseConverter.toDTO(knowledgeBase));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private List<Tool> bindRuntimeToolContext(
            List<Tool> runtimeTools,
            String userId,
            String chatSessionId,
            List<KnowledgeBaseDTO> knowledgeBases
    ) {
        List<Tool> boundTools = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            if (tool instanceof KnowledgeTools knowledgeTools) {
                boundTools.add(knowledgeTools.fork(userId, chatSessionId, knowledgeBases));
                continue;
            }
            boundTools.add(tool);
        }
        return boundTools;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            Arrays.stream(toolCallbacks)
                    .map(this::wrapToolCallback)
                    .forEach(callbacks::add);
        }
        return callbacks;
    }

    private List<ToolCallback> buildExternalToolCallbacks(List<ToolCallback> localCallbacks) {
        Set<String> localNames = localCallbacks.stream()
                .map(tc -> tc.getToolDefinition().name())
                .collect(Collectors.toSet());
        List<ToolCallback> external = new ArrayList<>();
        for (ToolCallbackProvider provider : toolCallbackProviders) {
            for (ToolCallback tc : provider.getToolCallbacks()) {
                if (!localNames.contains(tc.getToolDefinition().name())) {
                    external.add(wrapToolCallback(tc));
                }
            }
        }
        return external;
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return tool;
        } catch (Exception e) {
            throw new IllegalStateException("解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private ToolCallback wrapToolCallback(ToolCallback toolCallback) {
        return new HarnessToolCallbackProxy(toolCallback, harnessInterceptorChain);
    }

    private JChatMind buildAgentRuntime(
            String userId,
            Agent agent,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new JChatMind(
                userId,
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                harnessRunner
        );
    }

    public JChatMind create(String userId, String agentId, String chatSessionId) {
        Agent agent = loadAgent(agentId);
        AgentDTO currentAgentConfig = toAgentConfig(agent);
        List<Message> memory = loadMemory(userId, chatSessionId);
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(currentAgentConfig);
        List<Tool> runtimeTools = resolveRuntimeTools(currentAgentConfig);
        runtimeTools = bindRuntimeToolContext(runtimeTools, userId, chatSessionId, knowledgeBases);
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);
        toolCallbacks.addAll(buildExternalToolCallbacks(toolCallbacks));

        return buildAgentRuntime(
                userId,
                agent,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId
        );
    }
}
