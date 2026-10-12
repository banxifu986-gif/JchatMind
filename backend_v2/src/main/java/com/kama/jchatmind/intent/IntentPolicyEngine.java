package com.kama.jchatmind.intent;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

public class IntentPolicyEngine {

    private static final Set<String> SIDE_EFFECT_CAPABILITIES = Set.of(
            "sendEmail", "writeFile", "appendToFile", "deleteFile", "createDirectory"
    );

    public List<ToolCallback> select(List<ToolCallback> availableTools, IntentDecision decision) {
        if (availableTools == null || availableTools.isEmpty() || decision == null) {
            return List.of();
        }
        Set<String> candidateCapabilities = Set.copyOf(decision.candidateCapabilities());
        boolean sideEffectsAllowed = decision.route() == IntentRoute.TOOL
                && decision.riskLevel() == IntentRiskLevel.SIDE_EFFECT
                && !decision.requiresClarification();
        return availableTools.stream()
                .filter(callback -> callback != null && callback.getToolDefinition() != null)
                .filter(callback -> {
                    String capability = callback.getToolDefinition().name();
                    return candidateCapabilities.contains(capability)
                            && (sideEffectsAllowed || !SIDE_EFFECT_CAPABILITIES.contains(capability));
                })
                .toList();
    }
}
