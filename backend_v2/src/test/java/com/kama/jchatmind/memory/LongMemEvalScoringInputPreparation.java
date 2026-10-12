package com.kama.jchatmind.memory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class LongMemEvalScoringInputPreparation {

    private LongMemEvalScoringInputPreparation() {
    }

    static Result prepare(
            LongMemEvalReplayPreflightGate.Evidence evidence,
            List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions,
            List<LongMemEvalOfficialEvaluatorAdapter.Reference> references
    ) {
        AtomicReference<LongMemEvalOfficialEvaluatorAdapter.EvaluatorInputs> prepared = new AtomicReference<>();
        LongMemEvalReplayPreflightGate.Result preflight = LongMemEvalReplayPreflightGate.runIfReady(
                evidence,
                () -> prepared.set(LongMemEvalOfficialEvaluatorAdapter.adapt(predictions, references))
        );
        return new Result(preflight.ready(), preflight.blockers(), prepared.get());
    }

    record Result(
            boolean ready,
            List<String> blockers,
            LongMemEvalOfficialEvaluatorAdapter.EvaluatorInputs inputs
    ) {
    }
}
