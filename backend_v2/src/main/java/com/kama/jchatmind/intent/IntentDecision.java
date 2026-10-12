package com.kama.jchatmind.intent;

import java.util.List;
import java.util.Map;

public record IntentDecision(
        String decisionVersion,
        BusinessIntent businessIntent,
        QueryIntent queryIntent,
        IntentRoute route,
        IntentRiskLevel riskLevel,
        double confidence,
        Double confidenceMargin,
        Map<String, String> entities,
        List<String> candidateCapabilities,
        boolean requiresClarification,
        IntentDecisionSource decisionSource,
        String reasonCode,
        String policyVersion
) {

    public IntentDecision {
        if (isBlank(decisionVersion)) {
            throw new IllegalArgumentException("decisionVersion must not be blank");
        }
        if (businessIntent == null || route == null || riskLevel == null || decisionSource == null) {
            throw new IllegalArgumentException("intent decision enums must not be null");
        }
        if (Double.isNaN(confidence) || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (confidenceMargin != null
                && (Double.isNaN(confidenceMargin) || confidenceMargin < 0D || confidenceMargin > 1D)) {
            throw new IllegalArgumentException("confidenceMargin must be between 0 and 1 when present");
        }
        if (isBlank(reasonCode) || isBlank(policyVersion)) {
            throw new IllegalArgumentException("reasonCode and policyVersion must not be blank");
        }

        entities = entities == null ? Map.of() : Map.copyOf(entities);
        candidateCapabilities = candidateCapabilities == null
                ? List.of()
                : List.copyOf(candidateCapabilities);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
