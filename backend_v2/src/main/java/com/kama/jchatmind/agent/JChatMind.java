package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.ApprovalRequest;
import com.kama.jchatmind.agent.harness.proxy.HarnessExecutionContextHolder;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.intent.IntentDecision;
import com.kama.jchatmind.intent.IntentDecisionMetadata;
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
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
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
    private AgentToolExecutor toolExecutor;
    private ChatMemory chatMemory;
    private String chatSessionId;

    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;
    private static final Integer DEFAULT_TOOL_TIMEOUT_SECONDS = 30;

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
    private ToolSelectionPolicy toolSelectionPolicy;
    private String currentTraceId;
    private IntentDecisionMetadata currentIntentDecisionMetadata;
    private final Set<String> executedToolCallSignatures = new HashSet<>();
    private Set<String> currentTurnAllowedToolNames = Set.of();
    private String runEndReason;
    private String runOriginalGoal;
    private final List<String> completedToolSteps = new ArrayList<>();
    private boolean toolFailureObserved;

    public JChatMind() {
        this.toolTimeoutSeconds = DEFAULT_TOOL_TIMEOUT_SECONDS;
        this.toolSelectionPolicy = new ToolSelectionPolicy();
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
            HarnessRunner harnessRunner,
            AgentToolExecutor toolExecutor
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
        this.toolExecutor = toolExecutor;
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
        this.toolSelectionPolicy = new ToolSelectionPolicy();
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
            HarnessRunner harnessRunner,
            ToolSelectionPolicy toolSelectionPolicy,
            AgentToolExecutor toolExecutor
    ) {
        this(
                userId,
                agentId,
                name,
                description,
                systemPrompt,
                chatClient,
                maxMessages,
                memory,
                availableTools,
                availableKbs,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                harnessRunner,
                toolExecutor
        );
        this.toolSelectionPolicy = toolSelectionPolicy == null
                ? new ToolSelectionPolicy()
                : toolSelectionPolicy;
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
                            "[ToolCalling #%d]\n- id        : %s\n- name      : %s",
                            i + 1,
                            call.id(),
                            call.name()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("Agent tool calls: traceId={}, step={}, calls={}",
                currentTraceId,
                currentStepNumber,
                logMessage);
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
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO, this.userId);
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
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO, this.userId);
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
        String decisionCorrelationId = UUID.randomUUID().toString();
        IntentDecision intentDecision = toolSelectionPolicy.decide(
                latestUserQuery(),
                previousUserQuery(),
                currentTraceId,
                decisionCorrelationId
        );
        List<ToolCallback> turnTools = toolSelectionPolicy.select(availableTools, intentDecision);
        List<String> selectedToolNames = turnTools.stream()
                .map(tool -> tool.getToolDefinition().name())
                .toList();
        currentTurnAllowedToolNames = Set.copyOf(selectedToolNames);
        currentIntentDecisionMetadata = IntentDecisionMetadata.from(
                currentTraceId,
                decisionCorrelationId,
                intentDecision,
                selectedToolNames
        );
        log.info("Agent decision: traceId={}, step={}, intent={}, route={}, reason={}, allowedTools={}",
                currentTraceId,
                currentStepNumber,
                intentDecision.businessIntent(),
                intentDecision.route(),
                intentDecision.reasonCode(),
                selectedToolNames);
        String thinkPrompt = buildThinkPrompt();

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        this.lastChatResponse = streamChatResponse(prompt, thinkPrompt, turnTools);

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        this.chatMemory.add(this.chatSessionId, output);
        saveMessage(output);
        refreshPendingMessages();
        logToolCalls(toolCalls);

        return !toolCalls.isEmpty();
    }

    private ChatResponse streamChatResponse(
            Prompt prompt,
            String thinkPrompt,
            List<ToolCallback> turnTools
    ) {
        StringBuilder fullContent = new StringBuilder();
        List<ChatResponse> responseChunks = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(turnTools.toArray(new ToolCallback[0]))
                .stream()
                .chatResponse()
                .filter(this::hasAssistantOutput)
                .doOnNext(response -> appendContentDelta(response, fullContent))
                .collectList()
                .block();

        Assert.notEmpty(responseChunks, "Streamed chat response cannot be empty");

        ChatResponse lastResponse = responseChunks.get(responseChunks.size() - 1);
        AssistantMessage lastOutput = lastResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = responseChunks.stream()
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AssistantMessage::getToolCalls)
                .filter(toolCallList -> !toolCallList.isEmpty())
                .reduce((first, second) -> second)
                .orElse(List.of());
        AssistantMessage output = AssistantMessage.builder()
                .content(fullContent.toString())
                .media(lastOutput.getMedia())
                .properties(lastOutput.getMetadata())
                .toolCalls(toolCalls)
                .build();

        return ChatResponse.builder()
                .generations(List.of(new Generation(output, lastResponse.getResult().getMetadata())))
                .metadata(lastResponse.getMetadata())
                .build();
    }

    private String latestUserQuery() {
        return this.chatMemory.get(this.chatSessionId).stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .filter(StringUtils::hasText)
                .reduce((first, second) -> second)
                .orElse("");
    }

    private String previousUserQuery() {
        List<String> userQueries = this.chatMemory.get(this.chatSessionId).stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .filter(StringUtils::hasText)
                .toList();
        return userQueries.size() < 2 ? "" : userQueries.get(userQueries.size() - 2);
    }

    private void appendContentDelta(ChatResponse response, StringBuilder fullContent) {
        if (!hasAssistantOutput(response)) {
            return;
        }

        AssistantMessage output = response.getResult().getOutput();
        String contentDelta = output.getText();
        if (contentDelta == null || contentDelta.isEmpty()) {
            return;
        }

        fullContent.append(contentDelta);
        SseMessage message = SseMessage.builder()
                .type(SseMessage.Type.AI_CONTENT_DELTA)
                .payload(SseMessage.Payload.builder()
                        .contentDelta(contentDelta)
                        .stepNumber(currentStepNumber)
                        .build())
                .build();
        try {
            sseService.send(this.chatSessionId, message);
        } catch (Exception e) {
            log.warn("发送回答分块 SSE 失败: {}", e.getMessage());
        }
    }

    private boolean hasAssistantOutput(ChatResponse response) {
        return response != null
                && response.getResult() != null
                && response.getResult().getOutput() != null;
    }

    private String buildThinkPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                现在你是负责当前回合决策的 Agent。
                请根据当前对话上下文决定下一步动作。

                【工具选择规则】
                你需要根据当前用户问题的性质独立判断是否需要调用工具。用户此前对知识库或工具的随意评价不应影响你的工具选择——始终以当前问题的实际需求为准。

                1. 需要调用 KnowledgeTool 的场景：
                   - 用户询问的事实、规范、文档类问题，可能存在于知识库中
                   - 用户要求查找、检索、搜索特定信息
                   - 问题涉及专业领域知识，需要参考外部资料才能准确回答
                   - 你无法仅凭通用知识给出可靠答案的事实性问题

                2. 不需要调用 KnowledgeTool 的场景：
                   - 纯闲聊、打招呼（如"你好"、"今天过得怎么样"）
                   - 用户表达主观感受、意见或偏好，不涉及事实查询
                   - 通用常识、编程语法等无需检索也能准确回答的问题
                   - 用户只是对上一轮结果做出反馈或追问，无需额外检索

                3. 完成回答：
                   - 任务完成或无需进一步操作时，直接向用户输出最终自然语言答复，不要调用工具

                【最终回答排版要求】
                - 最终答复使用规范 Markdown。复杂内容使用标题和列表分层，简单问题用简短段落。
                - 标题单独成行，格式为“## 标题”，标题、段落和列表之间留空行。
                - 代码示例使用独立的三反引号代码块，语言标记后换行写代码；不要输出空代码块。
                - 列表中的每一项单独成行；表格的表头、分隔行和每一行数据也必须分别单独成行。
                - 不要把多个标题、编号条目或表格行连写在同一段/同一行，也不要输出没有换行的伪表格。

                【额外信息】
                - 你当前可访问的知识库：%s
                - 调用 KnowledgeTool 时，可以显式传入 kbIds 指定搜索范围；如果不传 kbIds，默认搜索当前 Agent 全部可访问知识库
                - 本回合实际允许调用的工具：%s
                - 未列出的工具不可调用；如果本回合没有允许工具，请直接回答或澄清问题
                """.formatted(formatKbSummary(), formatCurrentTurnTools()));

        if (previousToolCallSignature != null) {
            prompt.append("""

                    【重要提醒】
                    上一轮你已调用过工具（%s），请检查返回结果，避免重复相同的工具调用。如果结果已满足需求，请直接输出最终回答。
                    """.formatted(previousToolCallSignature));
        }

        if (!completedToolSteps.isEmpty()) {
            prompt.append("""

                    【本次运行进度】
                    已返回的工具步骤（结果正文见对话中的工具消息）：
                    %s
                    尚余工具执行预算：%d
                    """.formatted(String.join("\n", completedToolSteps), Math.max(0, MAX_STEPS - currentStepNumber)));
        }

        if (StringUtils.hasText(runOriginalGoal)) {
            prompt.append("""

                    【本次运行原始目标】
                    %s
                    请围绕此目标使用当前轮的最新用户请求和工具结果作答；若最新请求已切换话题，以最新请求为准。
                    """.formatted(runOriginalGoal));
        }

        String boundedConversationSummary = AgentMemoryCompressor.limitSummary(conversationSummary);
        if (StringUtils.hasText(boundedConversationSummary)) {
            prompt.append("""

                    【对话历史摘要】
                    以下是对此前对话内容的摘要，供你参考以保持上下文连贯性：
                    %s
                    """.formatted(boundedConversationSummary));
        }

        return prompt.toString();
    }

    private void compressMemoryIfNeeded() {
        conversationSummary = AgentMemoryCompressor.compress(
                chatClient, chatMemory.get(chatSessionId), conversationSummary);
    }

    private String formatKbSummary() {
        if (availableKbs == null || availableKbs.isEmpty()) {
            return "无";
        }
        return availableKbs.stream()
                .map(kb -> kb.getName() + "(" + kb.getId() + ")")
                .collect(Collectors.joining("、"));
    }

    private String formatCurrentTurnTools() {
        return currentTurnAllowedToolNames.isEmpty()
                ? "无"
                : String.join("、", currentTurnAllowedToolNames);
    }

    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");
        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }
        this.agentState = AgentState.EXECUTING;

        List<AssistantMessage.ToolCall> toolCalls = this.lastChatResponse.getResult().getOutput().getToolCalls();
        Set<String> duplicateToolCallIds = registerToolCallSignatures(toolCalls);
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
                toolCalls,
                currentTurnAllowedToolNames,
                duplicateToolCallIds,
                currentIntentDecisionMetadata
        );
        if (harnessResult.hasPendingApprovals()) {
            sendApprovalEvents(harnessResult);
            harnessRunner.awaitApprovals(harnessResult);
        }

        List<AssistantMessage.ToolCall> allowedToolCalls = toolCalls.stream()
                .filter(toolCall -> {
                    HarnessDecision decision = harnessResult.getDecision(toolCall.id());
                    return decision != null && decision.getStatus() == HarnessDecision.Status.ALLOW;
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

        saveMessage(toolResponseMessage);
        refreshPendingMessages();
        toolFailureObserved = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "FAILED".equals(classifyToolOutcome(response.responseData())));
        for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
            String outcome = classifyToolOutcome(response.responseData());
            log.info("Agent tool result: traceId={}, step={}, toolCallId={}, tool={}, outcome={}",
                    currentTraceId,
                    currentStepNumber,
                    response.id(),
                    response.name(),
                    outcome);
            completedToolSteps.add("step=%d call=%s tool=%s outcome=%s".formatted(
                    currentStepNumber,
                    response.id(),
                    response.name(),
                    outcome
            ));
            if (completedToolSteps.size() > MAX_STEPS) {
                completedToolSteps.remove(0);
            }
        }

        if (toolResponseMessage.getResponses().stream().anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    private String classifyToolOutcome(String responseData) {
        if (responseData != null && responseData.startsWith("错误：")) {
            return "FAILED";
        }
        if (responseData != null
                && (responseData.startsWith("[TOOL_") || responseData.startsWith("[APPROVAL_"))) {
            return "REJECTED";
        }
        return "RETURNED";
    }

    private Set<String> registerToolCallSignatures(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls.isEmpty()) {
            previousToolCallSignature = null;
            return Set.of();
        }
        previousToolCallSignature = toolCalls.stream()
                .map(tc -> tc.name() + "(" + tc.arguments() + ")")
                .collect(Collectors.joining("; "));
        Set<String> duplicateToolCallIds = new HashSet<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String signature = toolCall.name() + "(" + toolCall.arguments() + ")";
            if (!executedToolCallSignatures.add(signature)) {
                duplicateToolCallIds.add(toolCall.id());
            }
        }
        return duplicateToolCallIds;
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
            return toolExecutor.execute(
                    toolCallingManager, prompt, responseToExecute, allowedContexts,
                    new HarnessExecutionContextHolder.BatchMetadata(chatSessionId, agentId, userId, currentStepNumber),
                    Duration.ofSeconds(toolTimeoutSeconds)
            );
        } catch (TimeoutException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return unconfirmedToolResponse(allowedToolCalls);
        } catch (RejectedExecutionException exception) {
            runEndReason = "TOOL_EXECUTOR_BUSY";
            agentState = AgentState.FINISHED;
            return buildErrorResponse(allowedToolCalls, "工具执行资源已满，本次调用未执行，请稍后重试");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof CancellationException) {
                return unconfirmedToolResponse(allowedToolCalls);
            }
            log.error("工具执行异常: traceId={}, step={}", currentTraceId, currentStepNumber);
            return buildErrorResponse(allowedToolCalls, "工具执行失败");
        } catch (Exception exception) {
            log.error("工具执行异常: traceId={}, step={}, exceptionType={}",
                    currentTraceId, currentStepNumber, exception.getClass().getSimpleName());
            return buildErrorResponse(allowedToolCalls, "工具执行失败");
        }
    }

    private ToolResponseMessage unconfirmedToolResponse(List<AssistantMessage.ToolCall> calls) {
        runEndReason = "TOOL_OUTCOME_UNKNOWN";
        agentState = AgentState.FINISHED;
        return buildErrorResponse(calls, "工具等待已结束，执行结果未确认，请核实后再操作");
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
            finishWithoutToolCalls();
        }
    }

    private void finishWithoutToolCalls() {
        agentState = AgentState.FINISHED;
        if (currentIntentDecisionMetadata != null && currentIntentDecisionMetadata.requiresClarification()) {
            runEndReason = "CLARIFICATION_REQUIRED";
        } else if (toolFailureObserved) {
            runEndReason = "TOOL_FAILURE";
        }
    }

    private void handleFinalBudgetDecision(boolean moreToolsRequested) {
        if (!moreToolsRequested) {
            finishWithoutToolCalls();
            return;
        }
        runEndReason = "BUDGET_EXHAUSTED";
        agentState = AgentState.FINISHED;
        sendStatus(SseMessage.Type.AI_ERROR, "执行步数已用尽，任务尚未确认完成", currentStepNumber);
    }

    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }
        currentTraceId = UUID.randomUUID().toString();
        runEndReason = null;
        runOriginalGoal = latestUserQuery();
        completedToolSteps.clear();
        toolFailureObserved = false;

        try {
            this.agentState = AgentState.PLANNING;
            sendStatus(SseMessage.Type.AI_PLANNING, "Agent 正在分析请求...", 0);
            for (int i = 0; i <= MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                int currentStep = i + 1;
                this.currentStepNumber = currentStep;
                if (currentStep > MAX_STEPS) {
                    handleFinalBudgetDecision(think());
                } else {
                    step();
                }
            }
            if (agentState != AgentState.FINISHED) {
                runEndReason = "BUDGET_EXHAUSTED";
                agentState = AgentState.FINISHED;
                log.warn("Max steps reached, stopping agent with budget exhausted");
                sendStatus(SseMessage.Type.AI_ERROR, "执行步数已用尽，任务尚未确认完成", currentStepNumber);
            } else if (runEndReason == null) {
                runEndReason = "COMPLETED";
                sendStatus(SseMessage.Type.AI_DONE, "任务完成", currentStepNumber);
            } else if ("TOOL_OUTCOME_UNKNOWN".equals(runEndReason)) {
                sendStatus(SseMessage.Type.AI_ERROR, "工具执行结果未确认，请核实后再操作", currentStepNumber);
            } else if ("TOOL_EXECUTOR_BUSY".equals(runEndReason)) {
                sendStatus(SseMessage.Type.AI_ERROR, "工具执行资源已满，本次调用未执行，请稍后重试", currentStepNumber);
            } else if ("TOOL_FAILURE".equals(runEndReason)) {
                sendStatus(SseMessage.Type.AI_ERROR, "工具执行失败，任务未确认完成", currentStepNumber);
            } else {
                sendStatus(SseMessage.Type.AI_DONE, "需要补充信息后继续", currentStepNumber);
            }
            log.info("Agent run ended: traceId={}, reason={}, steps={}, toolResults={}",
                    currentTraceId,
                    runEndReason,
                    currentStepNumber,
                    completedToolSteps.size());
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            runEndReason = "RUN_FAILURE";
            log.error("Agent run failed: traceId={}, reason={}, step={}",
                    currentTraceId,
                    runEndReason,
                    currentStepNumber,
                    e);
            sendStatus(SseMessage.Type.AI_ERROR, "Agent 执行失败，请稍后重试", currentStepNumber);
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
