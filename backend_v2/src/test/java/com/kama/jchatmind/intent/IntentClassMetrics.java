package com.kama.jchatmind.intent;

record IntentClassMetrics(
        int support,
        int predicted,
        int truePositive,
        double precision,
        double recall,
        double f1
) {
}
