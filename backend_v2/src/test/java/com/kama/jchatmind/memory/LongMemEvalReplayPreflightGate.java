package com.kama.jchatmind.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class LongMemEvalReplayPreflightGate {

    private LongMemEvalReplayPreflightGate() {
    }

    static Result runIfReady(Evidence evidence, Runnable replay) {
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(replay);
        List<String> blockers = new ArrayList<>();
        if (!evidence.sourceAndEvaluatorPinsVerified()) {
            blockers.add("source_and_evaluator_pins_unverified");
        }
        if (!evidence.candidateManifestVerified()) {
            blockers.add("candidate_manifest_unverified");
        }
        if (!evidence.m2ReviewComplete()) {
            blockers.add("m2_review_incomplete");
        }
        if (!evidence.m2DecisionsApplied()) {
            blockers.add("m2_decisions_not_applied");
        }
        if (!evidence.isolatedSchemaVerified()) {
            blockers.add("isolated_schema_unverified");
        }
        if (!evidence.g2GatesEligible()) {
            blockers.add("g2_gates_not_eligible");
        }
        if (!evidence.officialEvaluatorAdapterReady()) {
            blockers.add("official_evaluator_adapter_not_ready");
        }
        if (!blockers.isEmpty()) {
            return new Result(false, List.copyOf(blockers));
        }
        replay.run();
        return new Result(true, List.of());
    }

    record Evidence(
            boolean sourceAndEvaluatorPinsVerified,
            boolean candidateManifestVerified,
            boolean m2ReviewComplete,
            boolean m2DecisionsApplied,
            boolean isolatedSchemaVerified,
            boolean g2GatesEligible,
            boolean officialEvaluatorAdapterReady
    ) {
    }

    record Result(boolean ready, List<String> blockers) {
    }
}
