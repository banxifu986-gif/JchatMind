package com.kama.jchatmind.intent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class InMemoryIntentDecisionTelemetry implements IntentDecisionTelemetry {

    private final List<IntentDecisionEvent> events = new ArrayList<>();
    private final int maxRecords;

    @Autowired
    public InMemoryIntentDecisionTelemetry(IntentRoutingProperties properties) {
        this(properties.getMaxTelemetryRecords());
    }

    public InMemoryIntentDecisionTelemetry(int maxRecords) {
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords must be positive");
        }
        this.maxRecords = maxRecords;
    }

    @Override
    public synchronized void record(IntentDecisionEvent event) {
        if (event == null) {
            return;
        }
        events.add(event);
        while (events.size() > maxRecords) {
            events.remove(0);
        }
    }

    public synchronized List<IntentDecisionEvent> getAll() {
        return List.copyOf(events);
    }
}
