package com.kama.jchatmind.agent.harness.proxy;

import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessRunMetadata;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.AgentToolExecutor;
import com.kama.jchatmind.agent.harness.HarnessToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * 工具调用守卫代理：before 拦截 → 执行 → after 改写 / onError 的成对 hook
 * 在单一调用栈内触发（契约 §1）。上下文经 ToolContext 显式传递（契约 §2）。
 */
public class HarnessToolCallbackProxy implements ToolCallback {

    /**
     * 一次工具调用的结果：blocked 非 null 表示被 per-call 守卫拦截，result 为拦截原因。
     */
    public record ToolCallOutcome(String result, HarnessDecision blocked) {
    }

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
        return callWithOutcome(toolInput, toolContext).result();
    }

    /**
     * 直调方（Workflow/Skill）使用：能区分“被拦截”与“正常返回”。
     * manager 路径走 {@link #call}，拦截结果以拦截原因字符串返回给模型。
     */
    public ToolCallOutcome callWithOutcome(String toolInput, ToolContext toolContext) {
        HarnessContext context = resolveContext(toolInput, toolContext);
        AgentToolExecutor.checkActive();
        HarnessDecision blocked = interceptorChain.executeBefore(context);
        if (blocked != null) {
            interceptorChain.executeOnBlocked(context, blocked);
            return new ToolCallOutcome(blocked.getMessage(), blocked);
        }
        try {
            String result = delegate.call(toolInput, toolContext);
            AgentToolExecutor.checkActive();
            String rewritten = interceptorChain.executeAfter(context, result);
            return new ToolCallOutcome(rewritten != null ? rewritten : result, null);
        } catch (Exception e) {
            interceptorChain.executeOnError(context, e);
            throw e;
        }
    }

    private HarnessContext resolveContext(String toolInput, ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalStateException(
                    "[HARNESS_CONTEXT_MISSING] 工具调用缺少 ToolContext，无法定位守卫上下文: "
                            + delegate.getToolDefinition().name());
        }
        Object metadataValue = toolContext.getContext().get(HarnessConstants.TOOL_CONTEXT_RUN_METADATA);
        if (!(metadataValue instanceof HarnessRunMetadata metadata)) {
            throw new IllegalStateException(
                    "[HARNESS_CONTEXT_MISSING] 工具调用 ToolContext 缺少运行元数据: "
                            + delegate.getToolDefinition().name());
        }
        Object contextsValue = toolContext.getContext().get(HarnessConstants.TOOL_CONTEXT_CALL_CONTEXTS);
        HarnessContext context = null;
        if (contextsValue instanceof Map<?, ?> signatureToQueues) {
            Object queue = signatureToQueues.get(
                    HarnessToolContexts.signature(delegate.getToolDefinition().name(), toolInput));
            if (queue instanceof java.util.Queue<?> concurrentQueue) {
                Object polled = concurrentQueue.poll();
                if (polled instanceof HarnessContext resolved) {
                    context = resolved;
                }
            }
        }
        if (context == null) {
            throw new IllegalStateException(
                    "[HARNESS_CONTEXT_MISSING] 工具 %s 的调用上下文缺失（未绑定或已耗尽）: %s".formatted(
                            delegate.getToolDefinition().name(), toolInput)
            );
        }
        return context;
    }
}
