package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class G2CurrentFixtureDefaultSwitchGateTest {

    @Test
    void blocksDefaultSwitchWhenQualityRefusalMultimodalOrPublicDiagnosticIsMissing() {
        G2CurrentFixtureDefaultSwitchGate.Evidence evidence = new G2CurrentFixtureDefaultSwitchGate.Evidence(
                "eligible_for_router_runtime_ab",
                "eligible_for_answer_citation_ab",
                "eligible_for_answer_citation_ab",
                "not_configured",
                "not_configured",
                false,
                "not_run"
        );

        G2CurrentFixtureDefaultSwitchGate.Report report =
                new G2CurrentFixtureDefaultSwitchGate().evaluate(evidence);

        assertThat(report.verdict()).isEqualTo("inconclusive");
        assertThat(report.reasons()).containsExactly(
                "answer_quality_not_passed",
                "answer_refusal_not_passed",
                "multimodal_authorization_refusal_citation_not_passed",
                "public_diagnostic_not_passed"
        );
    }

    @Test
    void allowsDefaultSwitchOnlyWhenEveryRequiredGatePasses() {
        G2CurrentFixtureDefaultSwitchGate.Evidence evidence = new G2CurrentFixtureDefaultSwitchGate.Evidence(
                "eligible_for_router_runtime_ab",
                "eligible_for_answer_citation_ab",
                "eligible_for_answer_citation_ab",
                "eligible_for_answer_quality_ab",
                "eligible_for_answer_refusal_ab",
                true,
                "eligible_for_full_validation"
        );

        G2CurrentFixtureDefaultSwitchGate.Report report =
                new G2CurrentFixtureDefaultSwitchGate().evaluate(evidence);

        assertThat(report.verdict()).isEqualTo("eligible_for_default_switch");
        assertThat(report.reasons()).isEmpty();
    }

    @Test
    void treatsMissingEvidenceAsNotPassed() {
        G2CurrentFixtureDefaultSwitchGate.Report report =
                new G2CurrentFixtureDefaultSwitchGate().evaluate(null);

        assertThat(report.verdict()).isEqualTo("inconclusive");
        assertThat(report.reasons()).containsExactly("gate_evidence_missing");
    }
}
