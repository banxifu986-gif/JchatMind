package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessResult;
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
    public void beforeExecution(HarnessContext context, HarnessResult result) {
        if (!harnessProperties.getCircuitBreaker().isEnabled()) {
            return;
        }
        HarnessDecision currentDecision = result.getDecision(context.getToolCallId());
        if (currentDecision != null && currentDecision.getStatus() != HarnessDecision.Status.ALLOW) {
            return;
        }
        Set<String> tools = harnessProperties.getCircuitBreaker().getTools().stream()
                .collect(Collectors.toSet());
        if (!tools.contains(context.getToolName())) {
            return;
        }
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.get(context.getToolName());
        if (circuitBreaker.allowRequest()) {
            return;
        }
        result.setDecision(
                context.getToolCallId(),
                HarnessDecision.circuitOpen(
                        "[CIRCUIT_BREAKER_OPEN] 工具 %s 暂时不可用，请稍后重试".formatted(context.getToolName())
                )
        );
    }

    @Override
    public void afterExecution(HarnessContext context, String toolResult) {
        if (!supports(context.getToolName())) {
            return;
        }
        circuitBreakerRegistry.get(context.getToolName()).recordSuccess();
    }

    @Override
    public void onError(HarnessContext context, Exception exception) {
        if (!supports(context.getToolName())) {
            return;
        }
        circuitBreakerRegistry.get(context.getToolName()).recordFailure();
    }

    @Override
    public int getOrder() {
        return 50;
    }

    private boolean supports(String toolName) {
        if (!harnessProperties.getCircuitBreaker().isEnabled()) {
            return false;
        }
        return harnessProperties.getCircuitBreaker().getTools().contains(toolName);
    }
}
