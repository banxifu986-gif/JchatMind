package com.kama.jchatmind.agent.harness.proxy;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public class HarnessToolCallbackProxy implements ToolCallback {

    private final ToolCallback delegate;
    private final HarnessInterceptorChain interceptorChain;

    public HarnessToolCallbackProxy(
            ToolCallback delegate,
            HarnessInterceptorChain interceptorChain
    ) {
        this.delegate = delegate;
        this.interceptorChain = interceptorChain;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        HarnessContext context = resolveContext(toolInput, toolContext);
        try {
            String result = delegate.call(toolInput, toolContext);
            interceptorChain.executeAfter(context, result);
            return result;
        } catch (Exception e) {
            interceptorChain.executeOnError(context, e);
            throw e;
        }
    }

    private HarnessContext resolveContext(String toolInput, ToolContext toolContext) {
        HarnessContext harnessContext = HarnessExecutionContextHolder.poll(delegate.getToolDefinition().name(), toolInput);
        if (harnessContext != null) {
            return harnessContext;
        }
        HarnessExecutionContextHolder.BatchMetadata metadata = HarnessExecutionContextHolder.getMetadata();
        HarnessContext fallbackContext = HarnessContext.builder()
                .sessionId(metadata == null ? null : metadata.sessionId())
                .agentId(metadata == null ? null : metadata.agentId())
                .userId(metadata == null ? null : metadata.userId())
                .toolName(delegate.getToolDefinition().name())
                .toolInput(toolInput)
                .stepNumber(metadata == null ? 0 : metadata.stepNumber())
                .build();
        fallbackContext.getAttributes().put(HarnessConstants.ATTRIBUTE_CONTEXT_BINDING_FALLBACK, true);
        return fallbackContext;
    }
}
