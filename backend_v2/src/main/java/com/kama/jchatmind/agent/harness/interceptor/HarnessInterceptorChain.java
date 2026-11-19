package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class HarnessInterceptorChain {

    private final List<HarnessInterceptor> interceptors;

    public HarnessInterceptorChain(List<HarnessInterceptor> interceptors) {
        this.interceptors = interceptors.stream()
                .sorted(Comparator.comparingInt(HarnessInterceptor::getOrder))
                .toList();
    }

    /**
     * 执行前守卫：按 order 依次调用，首个拦截决策短路返回；全部放行返回 null。
     */
    public HarnessDecision executeBefore(HarnessContext context) {
        for (HarnessInterceptor interceptor : interceptors) {
            HarnessDecision decision = interceptor.beforeExecution(context);
            if (decision != null && decision.getStatus() != HarnessDecision.Status.ALLOW) {
                return decision;
            }
        }
        return null;
    }

    /**
     * 执行后处理：按 order 依次传递结果，每个拦截器可改写（返回 null 表示不改写）。
     */
    public String executeAfter(HarnessContext context, String toolResult) {
        String current = toolResult;
        for (HarnessInterceptor interceptor : interceptors) {
            String rewritten = interceptor.afterExecution(context, current);
            if (rewritten != null) {
                current = rewritten;
            }
        }
        return current;
    }

    public void executeOnError(HarnessContext context, Exception exception) {
        for (HarnessInterceptor interceptor : interceptors) {
            interceptor.onError(context, exception);
        }
    }

    public void executeOnBlocked(HarnessContext context, HarnessDecision decision) {
        for (HarnessInterceptor interceptor : interceptors) {
            interceptor.onBlocked(context, decision);
        }
    }
}
