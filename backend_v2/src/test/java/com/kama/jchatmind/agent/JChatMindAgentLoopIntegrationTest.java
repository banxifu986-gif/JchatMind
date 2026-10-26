package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.ApprovalRequest;
import com.kama.jchatmind.agent.harness.approval.ApprovalStatus;
import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JChatMindAgentLoopIntegrationTest {

    @Test
    void shouldStopAfterRealMultiToolTimeoutWithoutCompletingOrStartingNextTool() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        ScriptedChatModel model = new ScriptedChatModel(List.of(List.of(
                toolCall("call-slow", "slow"), toolCall("call-later", "later"))));
        ToolCallback wrapped = new com.kama.jchatmind.agent.harness.proxy.HarnessToolCallbackProxy(
                callback(executions, input -> {
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return "late result";
                }), new HarnessInterceptorChain(List.of()));
        List<ChatMessageDTO> persisted = new ArrayList<>();
        List<SseMessage> events = new ArrayList<>();
        JChatMind agent = agent(model, wrapped, persisted, events);
        ReflectionTestUtils.setField(agent, "toolTimeoutSeconds", 1);
        try {
            agent.run();
            assertThat(executions).hasValue(1);
            assertThat(model.requestCount).hasValue(1);
            assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("TOOL_OUTCOME_UNKNOWN");
            assertThat(persisted).filteredOn(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL)
                    .extracting(message -> message.getMetadata().getToolResponse().id())
                    .containsExactly("call-slow", "call-later");
            assertThat(events).noneMatch(message -> message.getType() == SseMessage.Type.AI_DONE);
            assertThat(events).filteredOn(message -> message.getType() == SseMessage.Type.AI_ERROR)
                    .singleElement().satisfies(message -> {
                        assertThat(message.getPayload().getDone()).isFalse();
                        assertThat(message.getPayload().getStatusText()).contains("结果未确认");
                    });
        } finally {
            release.countDown();
            ((AgentToolExecutor) ReflectionTestUtils.getField(agent, "toolExecutor")).shutdown();
        }
    }

    @Test
    void shouldConsumeTwentiethToolResultAndCompleteOnFinalModelTurn() {
        ScriptedChatModel model = new ScriptedChatModel(20);
        ChatClient chatClient = ChatClient.create(model);
        List<ChatMessageDTO> persistedMessages = new ArrayList<>();
        List<SseMessage> sseMessages = new ArrayList<>();
        AtomicInteger toolExecutions = new AtomicInteger();
        HarnessRunner harnessRunner = newHarnessRunner();
        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                100,
                List.of(new UserMessage("请查询项目发布规范并总结")),
                List.of(callback(toolExecutions)),
                List.of(),
                "session-1",
                recordingSseService(sseMessages),
                messageService(persistedMessages),
                new ChatMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper()),
                harnessRunner,
                new com.kama.jchatmind.agent.AgentToolExecutor()
        );

        agent.run();

        assertThat(toolExecutions).hasValue(20);
        assertThat(model.requestCount).hasValue(21);
        assertThat(model.finalTurnToolResults.get()).contains("tool-result-20");
        assertThat(model.finalTurnContext.get())
                .contains("本次运行原始目标", "请查询项目发布规范并总结", "尚余工具执行预算：0", "call-20");
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("COMPLETED");
        assertThat(persistedMessages)
                .filteredOn(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL)
                .hasSize(20);
        assertThat(persistedMessages.get(persistedMessages.size() - 1).getContent())
                .isEqualTo("已根据最后一次工具结果完成总结。");
        assertThat(sseMessages)
                .filteredOn(message -> message.getType() == SseMessage.Type.AI_DONE)
                .singleElement()
                .satisfies(message -> assertThat(message.getPayload().getDone()).isTrue());
    }

    @Test
    void shouldReportBudgetExhaustionWhenFinalModelTurnRequestsMoreTools() {
        ScriptedChatModel model = new ScriptedChatModel(21);
        AtomicInteger toolExecutions = new AtomicInteger();
        List<SseMessage> sseMessages = new ArrayList<>();
        JChatMind agent = agent(model, callback(toolExecutions), new ArrayList<>(), sseMessages);

        agent.run();

        assertThat(toolExecutions).hasValue(20);
        assertThat(model.requestCount).hasValue(21);
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(sseMessages)
                .filteredOn(message -> message.getType() == SseMessage.Type.AI_ERROR)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getPayload().getDone()).isFalse();
                    assertThat(message.getPayload().getStatusText()).contains("尚未确认完成");
                });
    }

    @Test
    void shouldRejectRepeatedToolCallWithoutExecutingItTwice() {
        List<List<AssistantMessage.ToolCall>> turns = List.of(
                List.of(toolCall("call-1", "same-query")),
                List.of(toolCall("call-2", "same-query"))
        );
        ScriptedChatModel model = new ScriptedChatModel(turns);
        AtomicInteger toolExecutions = new AtomicInteger();
        List<ChatMessageDTO> persistedMessages = new ArrayList<>();
        JChatMind agent = agent(model, callback(toolExecutions, input -> "tool-result"), persistedMessages,
                new ArrayList<>());

        agent.run();

        assertThat(toolExecutions).hasValue(1);
        assertThat(model.finalTurnToolResults.get()).anyMatch(result -> result.startsWith("[TOOL_DUPLICATE]"));
        assertThat(persistedMessages)
                .filteredOn(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL)
                .hasSize(2);
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("COMPLETED");
    }

    @Test
    void shouldContinueAfterToolErrorAndConsumeRecoveredResult() {
        List<List<AssistantMessage.ToolCall>> turns = List.of(
                List.of(toolCall("call-failed", "first-query")),
                List.of(toolCall("call-retry", "retry-query"))
        );
        ScriptedChatModel model = new ScriptedChatModel(turns);
        AtomicInteger toolExecutions = new AtomicInteger();
        JChatMind agent = agent(model, callback(toolExecutions, input ->
                toolExecutions.get() == 1
                        ? throwToolFailure("临时检索失败")
                        : "tool-result-recovered"),
                new ArrayList<>(), new ArrayList<>());

        agent.run();

        assertThat(toolExecutions).hasValue(2);
        assertThat(model.finalTurnToolResults.get()).contains("错误：工具执行失败");
        assertThat(model.finalTurnToolResults.get()).contains("tool-result-recovered");
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("COMPLETED");
    }

    @Test
    void shouldEndWithErrorWhenToolFailureIsNotRecovered() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(List.of(toolCall("call-failed", "query"))));
        AtomicInteger toolExecutions = new AtomicInteger();
        List<SseMessage> sseMessages = new ArrayList<>();
        JChatMind agent = agent(model, callback(toolExecutions, input -> throwToolFailure("工具不可用")),
                new ArrayList<>(), sseMessages);

        agent.run();

        assertThat(toolExecutions).hasValue(1);
        assertThat(model.finalTurnToolResults.get()).contains("错误：工具执行失败");
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("TOOL_FAILURE");
        assertThat(sseMessages)
                .filteredOn(message -> message.getType() == SseMessage.Type.AI_ERROR)
                .singleElement()
                .satisfies(message -> assertThat(message.getPayload().getDone()).isFalse());
    }

    @Test
    void shouldPreserveEachResponseIdForMultipleCallsInOneTurn() {
        List<List<AssistantMessage.ToolCall>> turns = List.of(List.of(
                toolCall("call-a", "query-a"),
                toolCall("call-b", "query-b")
        ));
        ScriptedChatModel model = new ScriptedChatModel(turns);
        AtomicInteger toolExecutions = new AtomicInteger();
        JChatMind agent = agent(model, callback(toolExecutions, input ->
                "tool-result-" + toolExecutions.get()), new ArrayList<>(), new ArrayList<>());

        agent.run();

        assertThat(toolExecutions).hasValue(2);
        assertThat(model.finalTurnResponses.get())
                .extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("call-a", "call-b");
        assertThat(model.finalTurnResponses.get())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("tool-result-1", "tool-result-2");
    }

    @Test
    void shouldExecuteApprovedToolAndEmitApprovalEvent() throws Exception {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        HarnessRunner harnessRunner = approvalHarnessRunner(approvalStore);
        ScriptedChatModel model = new ScriptedChatModel(List.of(List.of(new AssistantMessage.ToolCall(
                "email-call",
                "function",
                "sendEmail",
                "{\"to\":\"owner@example.com\"}"
        ))));
        AtomicInteger toolExecutions = new AtomicInteger();
        List<SseMessage> events = new ArrayList<>();
        JChatMind agent = agent(
                model,
                callback("sendEmail", toolExecutions, input -> "email-sent"),
                new ArrayList<>(),
                events,
                "请发邮件给项目负责人",
                harnessRunner
        );
        CompletableFuture<Void> run = CompletableFuture.runAsync(agent::run);

        ApprovalRequest request = awaitApproval(harnessRunner);
        assertThat(harnessRunner.approve(request.getId())).isEqualTo(ApprovalStatus.APPROVED);
        run.get(3, TimeUnit.SECONDS);

        assertThat(toolExecutions).hasValue(1);
        assertThat(events)
                .filteredOn(message -> message.getType() == SseMessage.Type.TOOL_APPROVAL_REQUIRED)
                .singleElement()
                .satisfies(message -> assertThat(message.getPayload().getApprovalRequestId()).isEqualTo(request.getId()));
        assertThat(model.finalTurnToolResults.get()).contains("email-sent");
    }

    @Test
    void shouldNotExecuteToolRejectedByApprovalAndPassRejectionToNextTurn() throws Exception {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        HarnessRunner harnessRunner = approvalHarnessRunner(approvalStore);
        ScriptedChatModel model = new ScriptedChatModel(List.of(List.of(new AssistantMessage.ToolCall(
                "email-call",
                "function",
                "sendEmail",
                "{\"to\":\"owner@example.com\"}"
        ))));
        AtomicInteger toolExecutions = new AtomicInteger();
        JChatMind agent = agent(
                model,
                callback("sendEmail", toolExecutions, input -> "email-sent"),
                new ArrayList<>(),
                new ArrayList<>(),
                "请发邮件给项目负责人",
                harnessRunner
        );
        CompletableFuture<Void> run = CompletableFuture.runAsync(agent::run);

        ApprovalRequest request = awaitApproval(harnessRunner);
        assertThat(harnessRunner.reject(request.getId())).isEqualTo(ApprovalStatus.REJECTED);
        run.get(3, TimeUnit.SECONDS);

        assertThat(toolExecutions).hasValue(0);
        assertThat(model.finalTurnToolResults.get())
                .anyMatch(result -> result.startsWith("[APPROVAL_REJECTED]"));
    }

    @Test
    void shouldRejectModelToolOutsideCurrentTurnAllowlist() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(List.of(new AssistantMessage.ToolCall(
                "unauthorized-call",
                "function",
                "sendEmail",
                "{\"to\":\"owner@example.com\"}"
        ))));
        AtomicInteger knowledgeExecutions = new AtomicInteger();
        AtomicInteger emailExecutions = new AtomicInteger();
        JChatMind agent = agent(
                model,
                List.of(
                        callback(knowledgeExecutions),
                        callback("sendEmail", emailExecutions, input -> "email-sent")
                ),
                new ArrayList<>(),
                new ArrayList<>(),
                "请查询项目发布规范",
                newHarnessRunner()
        );

        agent.run();

        assertThat(emailExecutions).hasValue(0);
        assertThat(model.finalTurnToolResults.get())
                .anyMatch(result -> result.startsWith("[TOOL_NOT_ALLOWED]"));
    }

    private JChatMind agent(
            ScriptedChatModel model,
            ToolCallback callback,
            List<ChatMessageDTO> persistedMessages,
            List<SseMessage> sseMessages
    ) {
        return agent(
                model,
                callback,
                persistedMessages,
                sseMessages,
                "请查询项目发布规范并总结",
                newHarnessRunner()
        );
    }

    private JChatMind agent(
            ScriptedChatModel model,
            ToolCallback callback,
            List<ChatMessageDTO> persistedMessages,
            List<SseMessage> sseMessages,
            String userQuery,
            HarnessRunner harnessRunner
    ) {
        return agent(model, List.of(callback), persistedMessages, sseMessages, userQuery, harnessRunner);
    }

    private JChatMind agent(
            ScriptedChatModel model,
            List<ToolCallback> callbacks,
            List<ChatMessageDTO> persistedMessages,
            List<SseMessage> sseMessages,
            String userQuery,
            HarnessRunner harnessRunner
    ) {
        return new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                ChatClient.create(model),
                100,
                List.of(new UserMessage(userQuery)),
                callbacks,
                List.of(),
                "session-1",
                recordingSseService(sseMessages),
                messageService(persistedMessages),
                new ChatMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper()),
                harnessRunner,
                new com.kama.jchatmind.agent.AgentToolExecutor()
        );
    }

    private HarnessRunner newHarnessRunner() {
        HarnessProperties properties = new HarnessProperties();
        properties.getHumanApproval().setEnabled(false);
        properties.getCircuitBreaker().setEnabled(false);
        properties.getAudit().setEnabled(false);
        return new HarnessRunner(
                properties,
                new HarnessInterceptorChain(List.of()),
                new InMemoryApprovalStore(),
                new InMemoryAuditStore(properties)
        );
    }

    private ToolCallback callback(AtomicInteger toolExecutions) {
        return callback(toolExecutions, input -> "tool-result-" + toolExecutions.get());
    }

    private ToolCallback callback(AtomicInteger toolExecutions, Function<String, String> result) {
        return callback("KnowledgeTool", toolExecutions, result);
    }

    private ToolCallback callback(String name, AtomicInteger toolExecutions, Function<String, String> result) {
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
                toolExecutions.incrementAndGet();
                return result.apply(toolInput);
            }
        };
    }

    private AssistantMessage.ToolCall toolCall(String id, String query) {
        return new AssistantMessage.ToolCall(
                id,
                "function",
                "KnowledgeTool",
                "{\"query\":\"" + query + "\"}"
        );
    }

    private String throwToolFailure(String message) {
        throw new IllegalStateException(message);
    }

    private HarnessRunner approvalHarnessRunner(InMemoryApprovalStore approvalStore) {
        HarnessProperties properties = new HarnessProperties();
        properties.getHumanApproval().setEnabled(true);
        properties.getHumanApproval().setTools(List.of("sendEmail"));
        properties.getHumanApproval().setTimeoutSeconds(3);
        properties.getCircuitBreaker().setEnabled(false);
        properties.getAudit().setEnabled(false);
        return new HarnessRunner(
                properties,
                new HarnessInterceptorChain(List.of(
                        new HumanApprovalInterceptor(properties, approvalStore, null)
                )),
                approvalStore,
                new InMemoryAuditStore(properties)
        );
    }

    private ApprovalRequest awaitApproval(HarnessRunner harnessRunner) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<ApprovalRequest> pending = harnessRunner.getPendingApprovals("session-1");
            if (!pending.isEmpty()) {
                return pending.get(0);
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for Harness approval request");
    }

    private ChatMessageFacadeService messageService(List<ChatMessageDTO> persistedMessages) {
        return (ChatMessageFacadeService) java.lang.reflect.Proxy.newProxyInstance(
                ChatMessageFacadeService.class.getClassLoader(),
                new Class<?>[]{ChatMessageFacadeService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createChatMessage") && args[0] instanceof ChatMessageDTO dto) {
                        persistedMessages.add(dto);
                        return CreateChatMessageResponse.builder()
                                .chatMessageId(UUID.randomUUID().toString())
                                .build();
                    }
                    return null;
                }
        );
    }

    private SseService recordingSseService(List<SseMessage> sseMessages) {
        return new SseService() {
            @Override
            public SseEmitter connect(String chatSessionId, Long lastEventId) {
                return null;
            }

            @Override
            public void send(String chatSessionId, SseMessage message) {
                sseMessages.add(message);
            }
        };
    }

    private static class ScriptedChatModel implements ChatModel {
        private final List<List<AssistantMessage.ToolCall>> turns;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicReference<List<String>> finalTurnToolResults = new AtomicReference<>(List.of());
        private final AtomicReference<List<ToolResponseMessage.ToolResponse>> finalTurnResponses =
                new AtomicReference<>(List.of());
        private final AtomicReference<String> finalTurnContext = new AtomicReference<>("");

        private ScriptedChatModel(int toolTurnCount) {
            this.turns = java.util.stream.IntStream.rangeClosed(1, toolTurnCount)
                    .mapToObj(index -> List.of(toolCall("call-" + index, "query-" + index)))
                    .toList();
        }

        private ScriptedChatModel(List<List<AssistantMessage.ToolCall>> turns) {
            this.turns = turns;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return response(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(response(prompt));
        }

        private ChatResponse response(Prompt prompt) {
            int requestNumber = requestCount.incrementAndGet();
            if (requestNumber > turns.size()) {
                finalTurnContext.set(prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining("\n")));
                List<ToolResponseMessage.ToolResponse> responses = prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream())
                        .toList();
                finalTurnResponses.set(responses);
                finalTurnToolResults.set(responses.stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .toList());
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("已根据最后一次工具结果完成总结。")
                )));
            }
            AssistantMessage output = AssistantMessage.builder()
                    .content("")
                    .toolCalls(turns.get(requestNumber - 1))
                    .build();
            return new ChatResponse(List.of(new Generation(output)));
        }

        private AssistantMessage.ToolCall toolCall(String id, String query) {
            return new AssistantMessage.ToolCall(
                    id,
                    "function",
                    "KnowledgeTool",
                    "{\"query\":\"" + query + "\"}"
            );
        }
    }
}
