package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.circuit.CircuitBreaker;
import com.kama.jchatmind.agent.harness.circuit.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CircuitBreakerInterceptor implements HarnessInterceptor {

    private final HarnessProperties harnessProperties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerInterceptor(
            HarnessProperties harnessProperties,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.harnessProperties = harnessProperties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public HarnessDecision beforeExecution(HarnessContext context) {
        if (!supports(context.getToolName())) {
            return null;
        }
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.get(context.getToolName());
        if (circuitBreaker.allowRequest()) {
            return null;
        }
        return HarnessDecision.circuitOpen(
                "[CIRCUIT_BREAKER_OPEN] 工具 %s 暂时不可用，请稍后重试".formatted(context.getToolName())
        );
    }

    @Override
    public String afterExecution(HarnessContext context, String toolResult) {
        if (supports(context.getToolName())) {
            circuitBreakerRegistry.get(context.getToolName()).recordSuccess();
        }
        return null;
    }

    @Override
    public void onError(HarnessContext context, Exception exception) {
        if (supports(context.getToolName())) {
            circuitBreakerRegistry.get(context.getToolName()).recordFailure();
        }
    }

    @Override
    public void onBlocked(HarnessContext context, HarnessDecision decision) {
        // 熔断打开本身即拦截来源，无需额外记录
    }

    @Override
    public int getOrder() {
        return 50;
    }

    private boolean supports(String toolName) {
        if (!harnessProperties.getCircuitBreaker().isEnabled()) {
            return false;
        }
        Set<String> tools = harnessProperties.getCircuitBreaker().getTools().stream()
                .collect(Collectors.toSet());
        return tools.contains(toolName);
    }
}
