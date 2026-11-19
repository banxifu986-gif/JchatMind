package com.kama.jchatmind.agent.harness.audit;

import java.util.List;

public interface AuditStore {
    void record(ToolCallRecord record);

    /** 读取指定会话的全部审计记录，供测试与查询使用。 */
    List<ToolCallRecord> getRecords(String sessionId);
}
