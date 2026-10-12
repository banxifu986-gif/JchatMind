package com.kama.jchatmind.memory;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalReplayPreflightGateTest {

    @Test
    void blocksBeforeReplayWhenSourceM2OrG2EvidenceIsMissing() {
        AtomicInteger replayStarts = new AtomicInteger();
        LongMemEvalReplayPreflightGate.Evidence evidence =
                new LongMemEvalReplayPreflightGate.Evidence(
                        false, true, false, false, true, false, true
                );

        LongMemEvalReplayPreflightGate.Result result =
                LongMemEvalReplayPreflightGate.runIfReady(evidence, replayStarts::incrementAndGet);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockers()).containsExactly(
                "source_and_evaluator_pins_unverified",
                "m2_review_incomplete",
                "m2_decisions_not_applied",
                "g2_gates_not_eligible"
        );
        assertThat(replayStarts).hasValue(0);
    }

    @Test
    void blocksBeforeReplayWhenManifestSchemaOrEvaluatorIsNotReady() {
        AtomicInteger replayStarts = new AtomicInteger();
        LongMemEvalReplayPreflightGate.Evidence evidence =
                new LongMemEvalReplayPreflightGate.Evidence(
                        true, false, true, true, false, true, false
                );

        LongMemEvalReplayPreflightGate.Result result =
                LongMemEvalReplayPreflightGate.runIfReady(evidence, replayStarts::incrementAndGet);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockers()).containsExactly(
                "candidate_manifest_unverified",
                "isolated_schema_unverified",
                "official_evaluator_adapter_not_ready"
        );
        assertThat(replayStarts).hasValue(0);
    }

    @Test
    void startsReplayOnceWhenAllEvidenceIsVerified() {
        AtomicInteger replayStarts = new AtomicInteger();
        LongMemEvalReplayPreflightGate.Evidence evidence =
                new LongMemEvalReplayPreflightGate.Evidence(
                        true, true, true, true, true, true, true
                );

        LongMemEvalReplayPreflightGate.Result result =
                LongMemEvalReplayPreflightGate.runIfReady(evidence, replayStarts::incrementAndGet);

        assertThat(result.ready()).isTrue();
        assertThat(result.blockers()).isEmpty();
        assertThat(replayStarts).hasValue(1);
    }
}
