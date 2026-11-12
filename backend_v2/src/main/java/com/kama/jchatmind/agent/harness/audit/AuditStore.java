package com.kama.jchatmind.agent.harness.audit;

public interface AuditStore {
    void record(ToolCallRecord record);
}
