package com.kama.jchatmind.intent;

import java.time.Instant;
import java.util.List;

public record IntentDecisionEvent(
        Instant occurredAt,
        String traceId,
        String correlationId,
        String inputHash,
        String decisionVersion,
        String policyVersion,
        boolean shadow,
        BusinessIntent ruleIntent,
        IntentRoute ruleRoute,
        BusinessIntent candidateIntent,
        IntentRoute candidateRoute,
        IntentRiskLevel candidateRisk,
        IntentDecisionSource candidateSource,
        double candidateConfidence,
        Double candidateMargin,
        double minMargin,
        List<String> candidateCapabilities,
        String reasonCode
) {

    public IntentDecisionEvent {
        if (occurredAt == null
                || traceId == null || traceId.isBlank()
                || correlationId == null || correlationId.isBlank()
                || inputHash == null || !inputHash.matches("[0-9a-f]{64}")
                || decisionVersion == null || decisionVersion.isBlank()
                || policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("telemetry event must contain IDs, versions and a lowercase SHA-256 hash");
        }
        if (ruleIntent == null || ruleRoute == null || candidateIntent == null
                || candidateRoute == null || candidateRisk == null || candidateSource == null
                || reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("telemetry decision fields must not be null");
        }
        if (Double.isNaN(candidateConfidence) || candidateConfidence < 0D || candidateConfidence > 1D) {
            throw new IllegalArgumentException("candidateConfidence must be between 0 and 1");
        }
        if (Double.isNaN(minMargin) || minMargin < 0D || minMargin > 1D
                || (candidateMargin != null
                && (Double.isNaN(candidateMargin) || candidateMargin < 0D || candidateMargin > 1D))) {
            throw new IllegalArgumentException("candidate margin and threshold must be between 0 and 1");
        }
        candidateCapabilities = candidateCapabilities == null
                ? List.of()
                : List.copyOf(candidateCapabilities);
    }
}
