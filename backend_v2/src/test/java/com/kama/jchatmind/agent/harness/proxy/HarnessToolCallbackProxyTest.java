package com.kama.jchatmind.agent.harness.proxy;

import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptor;
import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessToolCallbackProxyTest {

    @AfterEach
    void tearDown() {
        HarnessExecutionContextHolder.clear();
    }

    @Test
    void shouldUseFallbackContextWithBatchMetadata() {
        AtomicReference<HarnessContext> captured = new AtomicReference<>();
        HarnessInterceptorChain chain = new HarnessInterceptorChain(List.of(new RecordingInterceptor(captured)));
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(new StubToolCallback(), chain);

        HarnessExecutionContextHolder.bind(
                List.of(),
                new HarnessExecutionContextHolder.BatchMetadata("session-1", "agent-1", "user-1", 2)
        );

        proxy.call("{\"to\":\"a\"}", new ToolContext(java.util.Map.of()));

        HarnessContext context = captured.get();
        assertEquals("session-1", context.getSessionId());
        assertEquals("agent-1", context.getAgentId());
        assertEquals("user-1", context.getUserId());
        assertEquals(2, context.getStepNumber());
        assertTrue(Boolean.TRUE.equals(context.getAttributes().get(HarnessConstants.ATTRIBUTE_CONTEXT_BINDING_FALLBACK)));
    }

    private static class RecordingInterceptor implements HarnessInterceptor {
        private final AtomicReference<HarnessContext> captured;

        private RecordingInterceptor(AtomicReference<HarnessContext> captured) {
            this.captured = captured;
        }

        @Override
        public void beforeExecution(HarnessContext context, HarnessResult result) {
        }

        @Override
        public void afterExecution(HarnessContext context, String toolResult) {
            captured.set(context);
        }

        @Override
        public void onError(HarnessContext context, Exception exception) {
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    private static class StubToolCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("sendEmail")
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
            return "ok";
        }
    }
}
