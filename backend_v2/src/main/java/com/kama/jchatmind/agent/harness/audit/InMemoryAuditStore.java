package com.kama.jchatmind.agent.harness.audit;

import com.kama.jchatmind.agent.harness.HarnessProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryAuditStore implements AuditStore {

    private final ConcurrentMap<String, List<ToolCallRecord>> records = new ConcurrentHashMap<>();
    private final HarnessProperties harnessProperties;

    public InMemoryAuditStore(HarnessProperties harnessProperties) {
        this.harnessProperties = harnessProperties;
    }

    @Override
    public void record(ToolCallRecord record) {
        List<ToolCallRecord> sessionRecords = records.computeIfAbsent(record.getSessionId(), key -> new ArrayList<>());
        synchronized (sessionRecords) {
            sessionRecords.add(record);
            int maxRecords = harnessProperties.getAudit().getMaxRecordsPerSession();
            while (sessionRecords.size() > maxRecords) {
                sessionRecords.remove(0);
            }
        }
    }

    @Override
    public List<ToolCallRecord> getBySession(String sessionId) {
        List<ToolCallRecord> sessionRecords = records.get(sessionId);
        if (sessionRecords == null) {
            return List.of();
        }
        synchronized (sessionRecords) {
            return List.copyOf(sessionRecords);
        }
    }
}
