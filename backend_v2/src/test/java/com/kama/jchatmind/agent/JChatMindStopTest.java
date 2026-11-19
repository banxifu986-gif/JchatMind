package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.loop.AgentCancelState;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 契约依据：specs/003-agent-loop-harness-refactor/contracts/hook-pipeline-contract.md §5（取消）。
 * spec 场景 4 验收：stop 后在飞工具中断、循环停步、SSE 以明确结束事件收尾、重复 stop 幂等。
 */
class JChatMindStopTest {

    @Test
    void shouldStopRunningAgentAndInterruptInFlightTool() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch toolInterrupted = new CountDownLatch(1);
        AtomicInteger modelRequests = new AtomicInteger();
        List<SseMessage> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        ChatClient chatClient = ChatClient.create(new ScriptedAlwaysToolsModel());
        ToolCallback slowTool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("KnowledgeTool")
                        .description("stub")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return DefaultToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String toolInput) {
                toolStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    toolInterrupted.countDown();
                }
                return "interrupted";
            }
        };
        AgentToolExecutor toolExecutor = new AgentToolExecutor();
        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                100,
                List.of(new UserMessage("帮我持续查询")),
                List.of(slowTool),
                List.of(),
                "session-1",
                recordingSseService(events),
                messageService(),
                new ChatMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper()),
                harnessRunner(),
                toolExecutor
        );

        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<?> runFuture = runner.submit(agent::run);
            assertThat(toolStarted.await(5, TimeUnit.SECONDS)).isTrue();

            agent.stop();
            assertThat(toolInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
            runFuture.get(10, TimeUnit.SECONDS);

            assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("CANCELLED");
            assertThat(ReflectionTestUtils.getField(agent, "agentState")).isEqualTo(AgentState.FINISHED);
            assertThat(events).extracting(SseMessage::getType)
                    .contains(SseMessage.Type.AI_ERROR);
            assertThat(events.stream()
                    .filter(message -> message.getType() == SseMessage.Type.AI_ERROR)
                    .map(message -> message.getPayload().getStatusText())
                    .toList())
                    .contains("用户已停止执行");

            // 重复 stop 幂等：不再抛异常、状态不变
            agent.stop();
            assertThat(ReflectionTestUtils.getField(agent, "agentState")).isEqualTo(AgentState.FINISHED);
        } finally {
            toolExecutor.shutdown();
            runner.shutdownNow();
        }
    }

    private SseService recordingSseService(List<SseMessage> events) {
        return new SseService() {
            @Override
            public SseEmitter connect(String chatSessionId, Long lastEventId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void send(String chatSessionId, SseMessage message) {
                events.add(message);
            }
        };
    }

    private ChatMessageFacadeService messageService() {
        ChatMessageFacadeService service = mock(ChatMessageFacadeService.class);
        CreateChatMessageResponse saved = mock(CreateChatMessageResponse.class);
        when(service.createChatMessage(any(com.kama.jchatmind.model.dto.ChatMessageDTO.class), eq("user-1")))
                .thenReturn(saved);
        when(saved.getChatMessageId()).thenReturn("message-1");
        return service;
    }

    private com.kama.jchatmind.agent.harness.HarnessRunner harnessRunner() {
        com.kama.jchatmind.agent.harness.HarnessProperties properties =
                new com.kama.jchatmind.agent.harness.HarnessProperties();
        properties.getHumanApproval().setEnabled(false);
        properties.getCircuitBreaker().setEnabled(false);
        properties.getAudit().setEnabled(false);
        return new com.kama.jchatmind.agent.harness.HarnessRunner(
                properties,
                new com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor(
                        properties,
                        new com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore(),
                        null
                ),
                new com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore(),
                new com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore(properties)
        );
    }

    /** 每轮都返回一个工具调用的脚本模型（永不自然停止）。 */
    private static class ScriptedAlwaysToolsModel implements org.springframework.ai.chat.model.ChatModel {
        private final AtomicInteger requestCount = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            return response();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(response());
        }

        private ChatResponse response() {
            int requestNumber = requestCount.incrementAndGet();
            AssistantMessage output = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-" + requestNumber, "function", "KnowledgeTool", "{}")))
                    .build();
            return new ChatResponse(List.of(new Generation(output)));
        }
    }
}
