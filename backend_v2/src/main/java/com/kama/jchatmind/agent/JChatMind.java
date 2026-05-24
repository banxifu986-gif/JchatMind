package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.ApprovalRequest;
import com.kama.jchatmind.agent.harness.proxy.HarnessExecutionContextHolder;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    private String userId;
    private String agentId;
    private String name;
    private String description;
    private String systemPrompt;
    private ChatClient chatClient;
    private AgentState agentState;
    private List<ToolCallback> availableTools;
    private List<KnowledgeBaseDTO> availableKbs;
    private ToolCallingManager toolCallingManager;
    private ChatMemory chatMemory;
    private String chatSessionId;

    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;
    private static final Integer DEFAULT_TOOL_TIMEOUT_SECONDS = 30;
    private static final int COMPRESSION_CHAR_THRESHOLD = 8000;
    private static final int KEEP_RECENT_MESSAGES = 8;

    private ChatOptions chatOptions;
    private SseService sseService;
    private ChatMessageConverter chatMessageConverter;
    private ChatMessageFacadeService chatMessageFacadeService;
    private ChatResponse lastChatResponse;
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();
    private final Integer toolTimeoutSeconds;
    private String previousToolCallSignature;
    private HarnessRunner harnessRunner;
    private int currentStepNumber;
    private String conversationSummary;

    public JChatMind() {
        this.toolTimeoutSeconds = DEFAULT_TOOL_TIMEOUT_SECONDS;
    }

    public JChatMind(
            String userId,
            String agentId,
            String name,
            String description,
            String systemPrompt,
            ChatClient chatClient,
            Integer maxMessages,
            List<Message> memory,
            List<ToolCallback> availableTools,
            List<KnowledgeBaseDTO> availableKbs,
            String chatSessionId,
            SseService sseService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            HarnessRunner harnessRunner
    ) {
        this.userId = userId;
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClient;
        this.availableTools = availableTools;
        this.availableKbs = availableKbs;
        this.chatSessionId = chatSessionId;
        this.sseService = sseService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.harnessRunner = harnessRunner;
        this.agentState = AgentState.IDLE;
        this.currentStepNumber = 0;

        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();

        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }
        this.chatMemory.add(chatSessionId, memory);

        this.toolTimeoutSeconds = DEFAULT_TOOL_TIMEOUT_SECONDS;
        this.previousToolCallSignature = null;

        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(userId, chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(userId, chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("Unsupported Message type: " + message.getClass().getName());
        }
    }

    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    private boolean think() {
        this.agentState = AgentState.THINKING;
        sendStatus(SseMessage.Type.AI_THINKING, "思考中...", currentStepNumber);
        compressMemoryIfNeeded();
        String thinkPrompt = buildThinkPrompt();

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        this.chatMemory.add(this.chatSessionId, output);
        saveMessage(output);
        refreshPendingMessages();
        logToolCalls(toolCalls);

        return !toolCalls.isEmpty();
    }

    private String buildThinkPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                现在你是负责当前回合决策的 Agent。
                请根据当前对话上下文决定下一步动作。

                【额外信息】
                - 你当前可访问的知识库：%s
                - 如果上下文不足，优先从知识库检索
                - 调用 KnowledgeTool 时，可以显式传入 kbIds 指定搜索范围；如果不传 kbIds，默认搜索当前 Agent 全部可访问知识库
                """.formatted(formatKbSummary()));

        if (previousToolCallSignature != null) {
            prompt.append("""

                    【重要提醒】
                    上一轮你已调用过工具（%s），请检查返回结果，避免重复相同的工具调用。如果结果已满足需求，请直接回答或调用 terminate。
                    """.formatted(previousToolCallSignature));
        }

        if (conversationSummary != null) {
            prompt.append("""

                    【对话历史摘要】
                    以下是对此前对话内容的摘要，供你参考以保持上下文连贯性：
                    %s
                    """.formatted(conversationSummary));
        }

        return prompt.toString();
    }

    private void compressMemoryIfNeeded() {
        List<Message> allMessages = this.chatMemory.get(this.chatSessionId);
        int totalChars = allMessages.stream()
                .mapToInt(m -> messageText(m) != null ? messageText(m).length() : 0)
                .sum();
        if (totalChars < COMPRESSION_CHAR_THRESHOLD) {
            return;
        }

        int keepFrom = Math.max(1, allMessages.size() - KEEP_RECENT_MESSAGES);
        if (keepFrom <= 1) {
            return;
        }

        try {
            List<Message> toCompress = new ArrayList<>(allMessages.subList(1, keepFrom));
            String newSummary = generateSummary(toCompress);
            if (!StringUtils.hasText(newSummary)) {
                return;
            }
            conversationSummary = (conversationSummary != null)
                    ? conversationSummary + "\n" + newSummary
                    : newSummary;

            allMessages.subList(1, keepFrom).clear();
            allMessages.add(1, new SystemMessage("【对话历史摘要】\n" + conversationSummary));
            log.info("Memory compressed: {} messages summarized, total chars before: {}", toCompress.size(), totalChars);
        } catch (Exception e) {
            log.warn("Failed to compress memory", e);
        }
    }

    private String messageText(Message msg) {
        if (msg instanceof AssistantMessage am) {
            return am.getText();
        }
        if (msg instanceof UserMessage um) {
            return um.getText();
        }
        if (msg instanceof SystemMessage sm) {
            return sm.getText();
        }
        if (msg instanceof ToolResponseMessage trm) {
            return trm.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        return "";
    }

    private String generateSummary(List<Message> toSummarize) {
        StringBuilder messagesText = new StringBuilder();
        for (Message msg : toSummarize) {
            String content = messageText(msg);
            if (content == null || content.isEmpty()) {
                continue;
            }
            if (content.length() > 1000) {
                content = content.substring(0, 1000) + "...";
            }
            messagesText.append(content).append("\n");
        }

        String summaryPrompt = conversationSummary != null
                ? "上一轮总结: " + conversationSummary + "\n\n对话:\n" + messagesText
                : "对话:\n" + messagesText;

        try {
            String response = this.chatClient.prompt()
                    .system("将以下对话总结为2-3句话，聚焦关键话题、用户意图和重要决策。只输出总结文本。")
                    .user(summaryPrompt)
                    .call()
                    .content();
            return response != null ? response.trim() : null;
        } catch (Exception e) {
            log.warn("Failed to generate summary", e);
            return null;
        }
    }

    private String formatKbSummary() {
        if (availableKbs == null || availableKbs.isEmpty()) {
            return "无";
        }
        return availableKbs.stream()
                .map(kb -> kb.getName() + "(" + kb.getId() + ")")
                .collect(Collectors.joining("、"));
    }

    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");
        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }
        this.agentState = AgentState.EXECUTING;

        List<AssistantMessage.ToolCall> toolCalls = this.lastChatResponse.getResult().getOutput().getToolCalls();
        updateDuplicateSignature(toolCalls);
        sendStatus(
                SseMessage.Type.AI_EXECUTING,
                "执行工具: " + summarizeToolNames(toolCalls),
                currentStepNumber
        );

        HarnessResult harnessResult = harnessRunner.beforeExecution(
                userId,
                agentId,
                chatSessionId,
                currentStepNumber,
                toolCalls
        );
        if (harnessResult.hasPendingApprovals()) {
            sendApprovalEvents(harnessResult);
            harnessRunner.awaitApprovals(harnessResult);
        }

        List<AssistantMessage.ToolCall> allowedToolCalls = toolCalls.stream()
                .filter(toolCall -> {
                    HarnessDecision decision = harnessResult.getDecision(toolCall.id());
                    return decision == null || decision.getStatus() == HarnessDecision.Status.ALLOW;
                })
                .toList();

        List<ToolResponseMessage.ToolResponse> syntheticResponses = buildSyntheticResponses(harnessResult, toolCalls);

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        long startNanos = System.nanoTime();
        List<HarnessContext> allowedContexts = allowedToolCalls.stream()
                .map(toolCall -> harnessResult.getContext(toolCall.id()))
                .filter(context -> context != null)
                .toList();

        ToolResponseMessage toolResponseMessage = executeWithTimeout(prompt, allowedToolCalls, allowedContexts);
        if (!syntheticResponses.isEmpty()) {
            List<ToolResponseMessage.ToolResponse> mergedResponses = new ArrayList<>(toolResponseMessage.getResponses());
            mergedResponses.addAll(syntheticResponses);
            toolResponseMessage = ToolResponseMessage.builder()
                    .responses(sortResponses(toolCalls, mergedResponses))
                    .build();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("工具执行耗时: {} ms", elapsedMs);

        this.chatMemory.add(this.chatSessionId, toolResponseMessage);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + " 的返回结果为: " + resp.responseData())
                .collect(Collectors.joining("\n"));
        log.info("工具调用结果: {}", collect);

        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses().stream().anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    private void updateDuplicateSignature(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls.isEmpty()) {
            previousToolCallSignature = null;
            return;
        }
        previousToolCallSignature = toolCalls.stream()
                .map(tc -> tc.name() + "(" + tc.arguments() + ")")
                .collect(Collectors.joining("; "));
    }

    private ToolResponseMessage executeWithTimeout(
            Prompt prompt,
            List<AssistantMessage.ToolCall> allowedToolCalls,
            List<HarnessContext> allowedContexts
    ) {
        if (allowedToolCalls.isEmpty()) {
            return ToolResponseMessage.builder()
                    .responses(List.of())
                    .build();
        }

        ChatResponse responseToExecute = buildFilteredChatResponse(allowedToolCalls);
        try {
            ToolExecutionResult result = CompletableFuture
                    .supplyAsync(() -> {
                        HarnessExecutionContextHolder.bind(
                                allowedContexts,
                                new HarnessExecutionContextHolder.BatchMetadata(
                                        chatSessionId,
                                        agentId,
                                        userId,
                                        currentStepNumber
                                )
                        );
                        try {
                            return toolCallingManager.executeToolCalls(prompt, responseToExecute);
                        } finally {
                            HarnessExecutionContextHolder.clear();
                        }
                    })
                    .orTimeout(toolTimeoutSeconds, TimeUnit.SECONDS)
                    .join();
            return extractToolResponse(result);
        } catch (Exception e) {
            log.error("工具执行异常（疑似调用了不存在的工具或超时）: {}", e.getMessage());
            return buildErrorResponse(allowedToolCalls, e.getMessage());
        }
    }

    private ToolResponseMessage extractToolResponse(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return (ToolResponseMessage) history.get(history.size() - 1);
    }

    private ToolResponseMessage buildErrorResponse(List<AssistantMessage.ToolCall> toolCalls, String errorMessage) {
        List<ToolResponseMessage.ToolResponse> errorResponses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            errorResponses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(),
                    toolCall.name(),
                    "错误：" + errorMessage
            ));
        }

        return ToolResponseMessage.builder()
                .responses(errorResponses)
                .build();
    }

    private void step() {
        if (think()) {
            execute();
        } else {
            agentState = AgentState.FINISHED;
        }
    }

    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            this.agentState = AgentState.PLANNING;
            sendStatus(SseMessage.Type.AI_PLANNING, "Agent 正在分析请求...", 0);
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                int currentStep = i + 1;
                this.currentStepNumber = currentStep;
                step();
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
            sendStatus(SseMessage.Type.AI_DONE, "任务完成", currentStepNumber);
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            sendStatus(SseMessage.Type.AI_DONE, "任务结束", currentStepNumber);
            throw new RuntimeException("Error running agent", e);
        }
    }

    private ChatResponse buildFilteredChatResponse(List<AssistantMessage.ToolCall> allowedToolCalls) {
        AssistantMessage originalOutput = this.lastChatResponse.getResult().getOutput();
        AssistantMessage filteredOutput = AssistantMessage.builder()
                .content(originalOutput.getText())
                .media(originalOutput.getMedia())
                .properties(originalOutput.getMetadata())
                .toolCalls(allowedToolCalls)
                .build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(filteredOutput, this.lastChatResponse.getResult().getMetadata())))
                .metadata(this.lastChatResponse.getMetadata())
                .build();
    }

    private List<ToolResponseMessage.ToolResponse> buildSyntheticResponses(
            HarnessResult harnessResult,
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        List<ToolResponseMessage.ToolResponse> syntheticResponses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            HarnessDecision decision = harnessResult.getDecision(toolCall.id());
            if (decision == null || decision.getStatus() == HarnessDecision.Status.ALLOW) {
                continue;
            }
            syntheticResponses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(),
                    toolCall.name(),
                    decision.getMessage()
            ));
            HarnessContext context = harnessResult.getContext(toolCall.id());
            if (context != null) {
                harnessRunner.recordSyntheticOutcome(context, decision);
            }
        }
        return syntheticResponses;
    }

    private List<ToolResponseMessage.ToolResponse> sortResponses(
            List<AssistantMessage.ToolCall> toolCalls,
            List<ToolResponseMessage.ToolResponse> responses
    ) {
        Map<String, Integer> orderMap = IntStream.range(0, toolCalls.size())
                .boxed()
                .collect(Collectors.toMap(index -> toolCalls.get(index).id(), index -> index));
        return responses.stream()
                .sorted(Comparator.comparingInt(response -> orderMap.getOrDefault(response.id(), Integer.MAX_VALUE)))
                .toList();
    }

    private void sendApprovalEvents(HarnessResult harnessResult) {
        harnessResult.getPendingContexts().stream()
                .map(context -> context.getAttributes().get(HarnessConstants.ATTRIBUTE_APPROVAL_REQUEST_ID))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .forEach(this::sendApprovalEvent);
    }

    private void sendApprovalEvent(String requestId) {
        ApprovalRequest request = harnessRunner.getApprovalRequest(requestId);
        if (request == null) {
            return;
        }
        SseMessage message = SseMessage.builder()
                .type(SseMessage.Type.TOOL_APPROVAL_REQUIRED)
                .payload(SseMessage.Payload.builder()
                        .statusText("等待审批: " + request.getToolName())
                        .approvalRequestId(request.getId())
                        .toolName(request.getToolName())
                        .toolInput(request.getToolInput())
                        .callCount(request.getCallCount())
                        .expiresAt(request.getExpiresAt().toEpochMilli())
                        .stepNumber(currentStepNumber)
                        .build())
                .build();
        try {
            sseService.send(this.chatSessionId, message);
        } catch (Exception e) {
            log.warn("发送审批 SSE 失败: {}", e.getMessage());
        }
    }

    private void sendStatus(SseMessage.Type type, String statusText, Integer stepNumber) {
        SseMessage message = SseMessage.builder()
                .type(type)
                .payload(SseMessage.Payload.builder()
                        .statusText(statusText)
                        .done(type == SseMessage.Type.AI_DONE)
                        .stepNumber(stepNumber)
                        .build())
                .build();
        try {
            sseService.send(this.chatSessionId, message);
        } catch (Exception e) {
            log.warn("发送状态 SSE 失败: {}", e.getMessage());
        }
    }

    private String summarizeToolNames(List<AssistantMessage.ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(AssistantMessage.ToolCall::name)
                .distinct()
                .collect(Collectors.joining("、"));
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
