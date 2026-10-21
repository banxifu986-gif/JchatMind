package com.kama.jchatmind.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkflowExecutionAuditRetentionJob {

    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 20;
    private static final String DELETE_SQL = """
            DELETE FROM workflow_execution_audit audit
            USING (
                SELECT audit_id
                FROM workflow_execution_audit
                WHERE expires_at <= CURRENT_TIMESTAMP
                ORDER BY expires_at ASC
                LIMIT ?
            ) expired
            WHERE audit.audit_id = expired.audit_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public WorkflowExecutionAuditRetentionJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void purgeExpired() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (purgeExpiredBatch() < BATCH_SIZE) {
                return;
            }
        }
    }

    int purgeExpiredBatch() {
        return jdbcTemplate.update(DELETE_SQL, BATCH_SIZE);
    }
}
