package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;

/**
 * 单 tool call 粒度的守卫 hook，在 HarnessToolCallbackProxy 的同一调用栈内成对触发。
 * 对齐 pi-main 的 beforeToolCall/afterToolCall 语义。
 */
public interface HarnessInterceptor {

    /**
     * 执行前守卫。返回 null 表示放行；返回状态非 ALLOW 的 HarnessDecision 即拦截，
     * Proxy 不执行工具实现，结果为拦截原因。
     */
    HarnessDecision beforeExecution(HarnessContext context);

    /**
     * 执行后处理，可改写返回给模型的结果；返回 null 表示不改写。
     */
    String afterExecution(HarnessContext context, String toolResult);

    /**
     * 工具实现抛异常时回调。
     */
    void onError(HarnessContext context, Exception exception);

    /**
     * 调用被 before 拦截时回调（如审计记录被拦截的调用）。
     */
    void onBlocked(HarnessContext context, HarnessDecision decision);

    int getOrder();
}
