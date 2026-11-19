package com.kama.jchatmind.agent.harness.proxy;

import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessToolContexts;
import com.kama.jchatmind.agent.harness.HarnessRunMetadata;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 契约依据：specs/003-agent-loop-harness-refactor/contracts/hook-pipeline-contract.md §2（上下文显式传递）。
 * spec 场景 3 验收：ToolContext 显式传递齐备 / 缺失时显式失败，无静默降级。
 */
class HarnessToolCallbackProxyToolContextTest {

    private static final HarnessRunMetadata RUN_METADATA = new HarnessRunMetadata(
            "user-7", "agent-7", "session-7", 3, null);

    @Test
    void shouldPassRunMetadataAndCallContextsThroughToolContext() {
        AtomicReference<ToolContext> received = new AtomicReference<>();
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                capturingStub("KnowledgeTool", received),
                new HarnessInterceptorChain(List.of())
        );

        proxy.call("{\"q\":\"x\"}", toolContext("KnowledgeTool", "{\"q\":\"x\"}", "call-9"));

        ToolContext toolContext = received.get();
        assertNotNull(toolContext, "工具实现必须收到显式传递的 ToolContext");
        HarnessRunMetadata metadata =
                (HarnessRunMetadata) toolContext.getContext().get(HarnessConstants.TOOL_CONTEXT_RUN_METADATA);
        assertNotNull(metadata, "运行元数据必须显式在场");
        assertEquals("user-7", metadata.userId());
        assertEquals("agent-7", metadata.agentId());
        assertEquals("session-7", metadata.sessionId());
        assertEquals(3, metadata.stepNumber());
        assertSame(RUN_METADATA.intentDecisionMetadata(), metadata.intentDecisionMetadata());
    }

    @Test
    void missingToolContextMustFailExplicitly() {
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                capturingStub("KnowledgeTool", new AtomicReference<>()),
                new HarnessInterceptorChain(List.of())
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> proxy.call("{}", null));

        assertTrue(exception.getMessage().contains("HARNESS_CONTEXT_MISSING"),
                "缺失必须显式失败并定位: " + exception.getMessage());
    }

    @Test
    void missingRunMetadataMustFailExplicitly() {
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                capturingStub("KnowledgeTool", new AtomicReference<>()),
                new HarnessInterceptorChain(List.of())
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> proxy.call("{}", new ToolContext(new HashMap<>())));

        assertTrue(exception.getMessage().contains("HARNESS_CONTEXT_MISSING"),
                "元数据缺失必须显式失败: " + exception.getMessage());
    }

    @Test
    void exhaustedCallContextQueueMustFailExplicitly() {
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(
                capturingStub("KnowledgeTool", new AtomicReference<>()),
                new HarnessInterceptorChain(List.of())
        );
        // 只绑定一个上下文，但同签名调用两次 —— 第二次必须显式失败而非静默降级
        ToolContext bound = toolContext("KnowledgeTool", "{}", "call-1");
        proxy.call("{}", bound);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> proxy.call("{}", bound));

        assertTrue(exception.getMessage().contains("HARNESS_CONTEXT_MISSING"),
                "队列耗尽必须显式失败: " + exception.getMessage());
    }

    private ToolCallback capturingStub(String name, AtomicReference<ToolContext> received) {
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
                received.set(toolContext);
                return "ok";
            }
        };
    }

    private ToolContext toolContext(String toolName, String toolInput, String toolCallId) {
        HarnessContext context = HarnessContext.builder()
                .sessionId(RUN_METADATA.sessionId())
                .agentId(RUN_METADATA.agentId())
                .userId(RUN_METADATA.userId())
                .toolCallId(toolCallId)
                .toolName(toolName)
                .toolInput(toolInput)
                .stepNumber(RUN_METADATA.stepNumber())
                .intentDecisionMetadata(RUN_METADATA.intentDecisionMetadata())
                .build();
        return new ToolContext(HarnessToolContexts.toContextData(RUN_METADATA, List.of(context)));
    }
}
