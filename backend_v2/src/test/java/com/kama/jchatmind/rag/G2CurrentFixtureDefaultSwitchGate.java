package com.kama.jchatmind.rag;

import java.util.List;

final class G2CurrentFixtureDefaultSwitchGate {

    Report evaluate(Evidence evidence) {
        if (evidence == null) {
            return new Report("inconclusive", List.of("gate_evidence_missing"));
        }
        List<String> reasons = new java.util.ArrayList<>();
        if (!"eligible_for_router_runtime_ab".equals(evidence.retrievalStatus())) {
            reasons.add("retrieval_comparison_not_passed");
        }
        if (!"eligible_for_answer_citation_ab".equals(evidence.fixedCitationStatus())) {
            reasons.add("fixed_citation_not_passed");
        }
        if (!"eligible_for_answer_citation_ab".equals(evidence.routerCitationStatus())) {
            reasons.add("router_citation_not_passed");
        }
        if (!"eligible_for_answer_quality_ab".equals(evidence.answerQualityStatus())) {
            reasons.add("answer_quality_not_passed");
        }
        if (!"eligible_for_answer_refusal_ab".equals(evidence.answerRefusalStatus())) {
            reasons.add("answer_refusal_not_passed");
        }
        if (!evidence.multimodalAuthorizationRefusalCitationPassed()) {
            reasons.add("multimodal_authorization_refusal_citation_not_passed");
        }
        if (!"eligible_for_full_validation".equals(evidence.publicDiagnosticStatus())) {
            reasons.add("public_diagnostic_not_passed");
        }
        return new Report(reasons.isEmpty() ? "eligible_for_default_switch" : "inconclusive", reasons);
    }

    record Evidence(
            String retrievalStatus,
            String fixedCitationStatus,
            String routerCitationStatus,
            String answerQualityStatus,
            String answerRefusalStatus,
            boolean multimodalAuthorizationRefusalCitationPassed,
            String publicDiagnosticStatus
    ) {
    }

    record Report(String verdict, List<String> reasons) {
    }
}
