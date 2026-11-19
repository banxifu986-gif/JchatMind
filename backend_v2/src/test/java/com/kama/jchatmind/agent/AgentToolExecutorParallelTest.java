package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessRunMetadata;
import com.kama.jchatmind.agent.harness.HarnessToolContexts;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.audit.ToolCallRecord;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import com.kama.jchatmind.agent.harness.interceptor.AuditTrailInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.CircuitBreakerInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.proxy.HarnessToolCallbackProxy;
import com.kama.jchatmind.agent.loop.AgentCancelState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 契约依据：specs/003-agent-loop-harness-refactor/contracts/hook-pipeline-contract.md §6（并行）。
 * spec 场景 5 验收：耗时 < 串行累加、结果按声明顺序回填、stop 全部中断且结果列表完整、失败隔离。
 * spec 场景 6 验收：并发压测下审计记录数 == 实际执行数、熔断计数正确。
 */
class AgentToolExecutorParallelTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // ---------- T301 场景 5 ----------

    @Test
    void shouldRunCallsConcurrentlyAndReorderResultsByDeclaration() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor();
        ToolCallingManager manager = perCallManager(callSleeping("call-a", "toolA", 300, "结果A"),
                callSleeping("call-b", "toolB", 300, "结果B"));
        Prompt prompt = promptWithToolContext(List.of(
                context("call-a", "toolA"), context("call-b", "toolB")));
        ChatResponse batch = batchResponse(call("call-a", "toolA"), call("call-b", "toolB"));

        long startedAt = System.nanoTime();
        ToolResponseMessage result = executor.executeParallel(
                manager, prompt, batch, List.of(call("call-a", "toolA"), call("call-b", "toolB")),
                TIMEOUT, null);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs).isLessThan(500L);
        assertThat(result.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("call-a", "call-b");
        assertThat(result.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("结果A", "结果B");
        executor.shutdown();
    }

    @Test
    void shouldIsolatePerCallFailureWithoutDiscardingOthers() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor();
        ToolCallingManager manager = perCallManager(
                failingCall("call-a", "toolA"),
                callSleeping("call-b", "toolB", 20, "结果B"));
        Prompt prompt = promptWithToolContext(List.of(
                context("call-a", "toolA"), context("call-b", "toolB")));
        ChatResponse batch = batchResponse(call("call-a", "toolA"), call("call-b", "toolB"));

        ToolResponseMessage result = executor.executeParallel(
                manager, prompt, batch, List.of(call("call-a", "toolA"), call("call-b", "toolB")),
                TIMEOUT, null);

        assertThat(result.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("call-a", "call-b");
        assertThat(result.getResponses().get(0).responseData()).contains("错误：");
        assertThat(result.getResponses().get(1).responseData()).isEqualTo("结果B");
        executor.shutdown();
    }

    @Test
    void shouldInterruptAllInFlightCallsAndThrowWhenUserStops() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor();
        AgentCancelState cancelState = new AgentCancelState();
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger interruptedCount = new AtomicInteger();
        ToolCallingManager manager = perCallManager(
                callAwaitingInterrupt("call-a", "toolA", bothStarted, null, interruptedCount),
                callAwaitingInterrupt("call-b", "toolB", bothStarted, null, interruptedCount));
        Prompt prompt = promptWithToolContext(List.of(
                context("call-a", "toolA"), context("call-b", "toolB")));
        ChatResponse batch = batchResponse(call("call-a", "toolA"), call("call-b", "toolB"));

        Thread runner = new Thread(() -> {
            try {
                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
                cancelState.cancel();
                // 不放行 release latch：中断是在飞调用的唯一解除路径（契约 §5）
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        runner.start();

        assertThatThrownBy(() -> executor.executeParallel(
                manager, prompt, batch, List.of(call("call-a", "toolA"), call("call-b", "toolB")),
                TIMEOUT, cancelState))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        runner.join(5000);

        assertThat(interruptedCount.get()).isEqualTo(2);
        executor.shutdown();
    }

    // ---------- T302 场景 6 并发压测 ----------

    @Test
    void shouldKeepAuditAndCircuitConsistentUnderParallelStress() throws Exception {
        HarnessProperties properties = new HarnessProperties();
        properties.getAudit().setEnabled(true);
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("failingTool"));
        properties.getCircuitBreaker().setFailureThreshold(1_000_000);
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        HarnessInterceptorChain chain = new HarnessInterceptorChain(List.of(
                new CircuitBreakerInterceptor(properties, circuitRegistry),
                new AuditTrailInterceptor(properties, auditStore)
        ));

        int rounds = 50;
        int callsPerRound = 8;
        AgentToolExecutor executor = new AgentToolExecutor();
        ToolCallingManager manager = stressManager(chain, callsPerRound);

        List<HarnessContext> contexts = new ArrayList<>();
        for (int i = 0; i < callsPerRound; i++) {
            contexts.add(context("stress-call-" + i, i % 2 == 0 ? "okTool" : "failingTool"));
        }

        for (int round = 0; round < rounds; round++) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (int i = 0; i < callsPerRound; i++) {
                calls.add(call("stress-call-" + i, i % 2 == 0 ? "okTool" : "failingTool"));
            }
            ChatResponse batch = batchResponse(calls.toArray(new AssistantMessage.ToolCall[0]));
            // 每轮重建 ToolContext（对齐 JChatMind 每 execute() 重新 applyRunToolContext 的真实行为）
            Prompt prompt = promptWithToolContext(contexts);
            ToolResponseMessage result = executor.executeParallel(
                    manager, prompt, batch, calls, TIMEOUT, null);
            assertThat(result.getResponses()).hasSize(callsPerRound);
        }

        List<ToolCallRecord> records = auditStore.getRecords("stress-session");
        assertThat(records).hasSize(rounds * callsPerRound);
        assertThat(circuitRegistry.get("failingTool").getFailureCount()).isEqualTo(rounds * callsPerRound / 2);
        assertThat(circuitRegistry.get("okTool").getState())
                .isEqualTo(com.kama.jchatmind.agent.harness.circuit.CircuitBreakerState.CLOSED);
        executor.shutdown();
    }

    // ---------- 测试基础设施 ----------

    private record ScriptedCall(String callId, String toolName, long sleepMs, String output, boolean fail,
                                CountDownLatch started, CountDownLatch release, AtomicInteger interruptedCount) {
    }

    /** 按 response 内单个 toolCall 路由到脚本行为的 manager stub。 */
    private ToolCallingManager perCallManager(ScriptedCall... scripts) {
        Map<String, ScriptedCall> byId = new HashMap<>();
        for (ScriptedCall script : scripts) {
            byId.put(script.callId(), script);
        }
        ToolCallingManager manager = mock(ToolCallingManager.class);
        doAnswer(invocation -> {
            ChatResponse response = invocation.getArgument(1);
            AssistantMessage.ToolCall toolCall = response.getResult().getOutput().getToolCalls().get(0);
            ScriptedCall script = byId.get(toolCall.id());
            if (script == null) {
                throw new IllegalStateException("未预期的工具调用: " + toolCall.id());
            }
            if (script.started() != null) {
                script.started().countDown();
            }
            try {
                if (script.release() != null) {
                    script.release().await();
                } else if (script.sleepMs() > 0) {
                    Thread.sleep(script.sleepMs());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (script.interruptedCount() != null) {
                    script.interruptedCount().incrementAndGet();
                }
                return ToolExecutionResult.builder()
                        .conversationHistory(List.of(toolResponseMessage(toolCall.id(), toolCall.name(), "被中断")))
                        .build();
            }
            if (script.fail()) {
                throw new IllegalStateException("工具执行失败: " + toolCall.id());
            }
            return ToolExecutionResult.builder()
                    .conversationHistory(List.of(toolResponseMessage(toolCall.id(), toolCall.name(), script.output())))
                    .build();
        }).when(manager).executeToolCalls(any(), any());
        return manager;
    }

    /** 压测 manager：按 toolName 路由到真实 Proxy（审计 + 熔断真实生效）。 */
    private ToolCallingManager stressManager(HarnessInterceptorChain chain, int callsPerRound) {
        Map<String, HarnessToolCallbackProxy> proxies = new ConcurrentHashMap<>();
        proxies.put("okTool", new HarnessToolCallbackProxy(stubTool("okTool", input -> "ok"), chain));
        proxies.put("failingTool", new HarnessToolCallbackProxy(stubTool("failingTool", input -> {
            throw new IllegalStateException("boom");
        }), chain));

        ToolCallingManager manager = mock(ToolCallingManager.class);
        doAnswer(invocation -> {
            ChatResponse response = invocation.getArgument(1);
            Prompt prompt = invocation.getArgument(0);
            AssistantMessage.ToolCall toolCall = response.getResult().getOutput().getToolCalls().get(0);
            HarnessToolCallbackProxy proxy = proxies.get(toolCall.name());
            ToolContext toolContext = new ToolContext(((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions()).getToolContext());
            String output = proxy.callWithOutcome(toolCall.arguments(), toolContext).result();
            return ToolExecutionResult.builder()
                    .conversationHistory(List.of(toolResponseMessage(toolCall.id(), toolCall.name(), output)))
                    .build();
        }).when(manager).executeToolCalls(any(), any());
        return manager;
    }

    private ToolCallback stubTool(String name, java.util.function.Function<String, String> body) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description("stub").inputSchema("{}").build();
            }

            @Override
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return org.springframework.ai.tool.metadata.DefaultToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String toolInput) {
                return body.apply(toolInput);
            }
        };
    }

    private ScriptedCall callSleeping(String callId, String toolName, long sleepMs, String output) {
        return new ScriptedCall(callId, toolName, sleepMs, output, false, null, null, null);
    }

    private ScriptedCall failingCall(String callId, String toolName) {
        return new ScriptedCall(callId, toolName, 0, null, true, null, null, null);
    }

    private ScriptedCall callAwaitingInterrupt(String callId, String toolName,
                                               CountDownLatch started, CountDownLatch ignored,
                                               AtomicInteger interruptedCount) {
        // release 为永不放行的 latch：中断是在飞调用的唯一解除路径（契约 §5）
        return new ScriptedCall(callId, toolName, 0, null, false, started,
                new CountDownLatch(1), interruptedCount);
    }

    private ToolResponseMessage toolResponseMessage(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }

    private AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{\"q\":1}");
    }

    private HarnessContext context(String callId, String toolName) {
        return HarnessContext.builder()
                .sessionId("stress-session")
                .agentId("agent-1")
                .userId("user-1")
                .toolCallId(callId)
                .toolName(toolName)
                .toolInput("{\"q\":1}")
                .stepNumber(1)
                .build();
    }

    private Prompt promptWithToolContext(List<HarnessContext> contexts) {
        Map<String, Object> data = HarnessToolContexts.toContextData(
                new HarnessRunMetadata("user-1", "agent-1", "stress-session", 1, null), contexts);
        org.springframework.ai.model.tool.ToolCallingChatOptions options = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolContext(data)
                .build();
        return Prompt.builder().messages(List.of(new AssistantMessage("stress"))).chatOptions(options).build();
    }

    private ChatResponse batchResponse(AssistantMessage.ToolCall... calls) {
        AssistantMessage output = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
        return new ChatResponse(List.of(new Generation(output)));
    }
}
