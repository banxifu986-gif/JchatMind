package com.kama.jchatmind.intent;

@FunctionalInterface
public interface IntentDecisionTelemetry {

    void record(IntentDecisionEvent event);

    static IntentDecisionTelemetry noop() {
        return event -> {
        };
    }
}
