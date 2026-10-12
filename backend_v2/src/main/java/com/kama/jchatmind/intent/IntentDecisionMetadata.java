package com.kama.jchatmind.intent;

import java.util.List;

public record IntentDecisionMetadata(
        String traceId,
        String correlationId,
        String decisionVersion,
        String policyVersion,
        BusinessIntent businessIntent,
        QueryIntent queryIntent,
        IntentRoute route,
        IntentRiskLevel riskLevel,
        double confidence,
        Double confidenceMargin,
        List<String> candidateCapabilities,
        List<String> selectedToolNames,
        boolean requiresClarification,
        IntentDecisionSource decisionSource,
        String reasonCode
) {

    public IntentDecisionMetadata {
        if (traceId == null || traceId.isBlank()
                || correlationId == null || correlationId.isBlank()
                || decisionVersion == null || decisionVersion.isBlank()
                || policyVersion == null || policyVersion.isBlank()
                || businessIntent == null || route == null || riskLevel == null || decisionSource == null
                || reasonCode == null || reasonCode.isBlank()
                || (confidenceMargin != null
                && (Double.isNaN(confidenceMargin) || confidenceMargin < 0D || confidenceMargin > 1D))) {
            throw new IllegalArgumentException("intent audit metadata is incomplete");
        }
        candidateCapabilities = candidateCapabilities == null
                ? List.of()
                : List.copyOf(candidateCapabilities);
        selectedToolNames = selectedToolNames == null
                ? List.of()
                : List.copyOf(selectedToolNames);
    }

    public static IntentDecisionMetadata from(
            String traceId,
            String correlationId,
            IntentDecision decision,
            List<String> selectedToolNames
    ) {
        if (decision == null) {
            throw new IllegalArgumentException("intent decision must not be null");
        }
        return new IntentDecisionMetadata(
                traceId,
                correlationId,
                decision.decisionVersion(),
                decision.policyVersion(),
                decision.businessIntent(),
                decision.queryIntent(),
                decision.route(),
                decision.riskLevel(),
                decision.confidence(),
                decision.confidenceMargin(),
                decision.candidateCapabilities(),
                selectedToolNames,
                decision.requiresClarification(),
                decision.decisionSource(),
                decision.reasonCode()
        );
    }
}
