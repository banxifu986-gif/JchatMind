package com.kama.jchatmind.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowExecutionAuditRetentionJobTest {

    @Test
    void shouldDeleteOnlyExpiredRowsInBoundedBatches() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(500);
        WorkflowExecutionAuditRetentionJob job = new WorkflowExecutionAuditRetentionJob(jdbcTemplate);

        int deleted = job.purgeExpiredBatch();

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var batchCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(jdbcTemplate).update(sqlCaptor.capture(), batchCaptor.capture());
        assertThat(deleted).isEqualTo(500);
        assertThat(batchCaptor.getValue()).isEqualTo(500);
        assertThat(sqlCaptor.getValue()).contains("expires_at <= CURRENT_TIMESTAMP", "LIMIT ?");
    }
}
