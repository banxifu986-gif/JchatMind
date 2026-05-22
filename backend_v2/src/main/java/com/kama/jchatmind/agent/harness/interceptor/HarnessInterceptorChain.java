package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessResult;
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

    public void executeBefore(HarnessContext context, HarnessResult result) {
        for (HarnessInterceptor interceptor : interceptors) {
            interceptor.beforeExecution(context, result);
        }
    }

    public void executeAfter(HarnessContext context, String toolResult) {
        for (HarnessInterceptor interceptor : interceptors) {
            interceptor.afterExecution(context, toolResult);
        }
    }

    public void executeOnError(HarnessContext context, Exception exception) {
        for (HarnessInterceptor interceptor : interceptors) {
            interceptor.onError(context, exception);
        }
    }
}
