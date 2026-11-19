package com.kama.jchatmind.agent.harness.proxy;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessToolContexts;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessRunMetadata;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.audit.ToolCallRecord;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import com.kama.jchatmind.agent.harness.interceptor.AuditTrailInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.CircuitBreakerInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约依据：specs/003-agent-loop-harness-refactor/contracts/hook-pipeline-contract.md §1（成对 hook）。
 * spec 场景 1 验收：before 拦截时工具实现零执行且结果含原因 / after 可改写 / 每 call 恰好一条审计记录。
 */
class HarnessToolCallbackProxyPairedHookTest {

    private static final HarnessRunMetadata RUN_METADATA = new HarnessRunMetadata(
            "user-1", "agent-1", "session-1", 1, null);

    @Test
    void blockedBeforeShouldSkipToolExecutionAndAuditTheBlock() {
        HarnessProperties properties = defaultProperties();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        circuitRegistry.get("databaseQuery").recordFailure();
        circuitRegistry.get("databaseQuery").recordFailure();
        circuitRegistry.get("databaseQuery").recordFailure();

        AtomicInteger delegateInvocations = new AtomicInteger();
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                stubToolCallback("databaseQuery", args -> {
                    delegateInvocations.incrementAndGet();
                    return "should-not-run";
                }),
                new HarnessInterceptorChain(List.of(
                        new CircuitBreakerInterceptor(properties, circuitRegistry),
                        new AuditTrailInterceptor(properties, auditStore)
                ))
        );

        String result = proxy.call("{\"sql\":\"SELECT 1\"}", toolContext("databaseQuery", "{\"sql\":\"SELECT 1\"}"));

        assertEquals(0, delegateInvocations.get(), "被拦截的调用不得执行工具实现");
        assertTrue(result.contains("CIRCUIT_BREAKER_OPEN"), "拦截结果必须携带原因: " + result);
        List<ToolCallRecord> records = auditStore.getRecords("session-1");
        assertEquals(1, records.size(), "每个 tool call 恰好一条审计记录");
        assertEquals(ToolCallRecord.Outcome.CIRCUIT_OPEN, records.get(0).getOutcome());
    }

    @Test
    void afterHookShouldBeAbleToRewriteToolResult() {
        HarnessProperties properties = defaultProperties();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        HarnessInterceptor rewritingInterceptor = new HarnessInterceptor() {
            @Override
            public HarnessDecision beforeExecution(HarnessContext context) {
                return null;
            }

            @Override
            public String afterExecution(HarnessContext context, String toolResult) {
                return toolResult + "|REWRITTEN";
            }

            @Override
            public void onError(HarnessContext context, Exception exception) {
            }

            @Override
            public void onBlocked(HarnessContext context, HarnessDecision decision) {
            }

            @Override
            public int getOrder() {
                return 300;
            }
        };

        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                stubToolCallback("KnowledgeTool", args -> "原始结果"),
                new HarnessInterceptorChain(List.of(
                        new AuditTrailInterceptor(properties, auditStore),
                        rewritingInterceptor
                ))
        );

        String result = proxy.call("{}", toolContext("KnowledgeTool", "{}"));

        assertEquals("原始结果|REWRITTEN", result, "after hook 的改写结果必须成为模型最终收到的结果");
        List<ToolCallRecord> records = auditStore.getRecords("session-1");
        assertEquals(1, records.size());
        // 锁定 order=9000 语义：审计记录的是改写后（模型最终收到）的结果
        assertTrue(records.get(0).getToolResult().contains("|REWRITTEN"),
                "审计所见必须是改写后结果: " + records.get(0).getToolResult());
    }

    @Test
    void errorShouldTriggerOnErrorAndStillAudit() {
        HarnessProperties properties = defaultProperties();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                stubToolCallback("KnowledgeTool", args -> {
                    throw new IllegalStateException("boom");
                }),
                new HarnessInterceptorChain(List.of(new AuditTrailInterceptor(properties, auditStore)))
        );

        assertThrows(IllegalStateException.class,
                () -> proxy.call("{}", toolContext("KnowledgeTool", "{}")));

        List<ToolCallRecord> records = auditStore.getRecords("session-1");
        assertEquals(1, records.size());
        assertEquals(ToolCallRecord.Outcome.ERROR, records.get(0).getOutcome());
        assertEquals("boom", records.get(0).getErrorMessage());
    }

    @Test
    void eachToolCallShouldProduceExactlyOneAuditRecord() {
        HarnessProperties properties = defaultProperties();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                stubToolCallback("KnowledgeTool", args -> "ok"),
                new HarnessInterceptorChain(List.of(new AuditTrailInterceptor(properties, auditStore)))
        );

        proxy.call("{\"q\":\"a\"}", toolContext("KnowledgeTool", "{\"q\":\"a\"}"));
        proxy.call("{\"q\":\"a\"}", toolContext("KnowledgeTool", "{\"q\":\"a\"}"));

        assertEquals(2, auditStore.getRecords("session-1").size(), "两次调用恰好两条记录");
    }

    private ToolCallback stubToolCallback(String name, ToolBody body) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name)
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
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return body.run(toolInput);
            }
        };
    }

    private ToolContext toolContext(String toolName, String toolInput) {
        HarnessContext context = HarnessContext.builder()
                .sessionId(RUN_METADATA.sessionId())
                .agentId(RUN_METADATA.agentId())
                .userId(RUN_METADATA.userId())
                .toolCallId("call-1")
                .toolName(toolName)
                .toolInput(toolInput)
                .stepNumber(RUN_METADATA.stepNumber())
                .build();
        return new ToolContext(HarnessToolContexts.toContextData(RUN_METADATA, List.of(context)));
    }

    private HarnessProperties defaultProperties() {
        HarnessProperties properties = new HarnessProperties();
        properties.getHumanApproval().setEnabled(true);
        properties.getHumanApproval().setTimeoutSeconds(30);
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("databaseQuery", "sendEmail"));
        properties.getCircuitBreaker().setFailureThreshold(3);
        properties.getAudit().setEnabled(true);
        return properties;
    }

    private interface ToolBody {
        String run(String toolInput);
    }
}
