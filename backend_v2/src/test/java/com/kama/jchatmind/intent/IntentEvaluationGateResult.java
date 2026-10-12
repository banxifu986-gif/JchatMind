package com.kama.jchatmind.intent;

import java.util.List;

record IntentEvaluationGateResult(
        boolean eligible,
        List<String> blockers
) {
}
