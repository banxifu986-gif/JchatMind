package com.kama.jchatmind.agent.harness.audit;

import java.util.List;

public interface AuditStore {
    void record(ToolCallRecord record);

    List<ToolCallRecord> getBySession(String sessionId);
}
