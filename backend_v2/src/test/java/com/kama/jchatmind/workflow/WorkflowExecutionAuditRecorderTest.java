package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowExecutionAuditRecorderTest {

    @Test
    void shouldPersistOnlyHashesAndStructuredStepMetadataWithNinetyDayExpiry() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkflowExecutionAuditRecorder recorder = new WorkflowExecutionAuditRecorder(
                jdbcTemplate,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC)
        );
        WorkflowPlan plan = new WorkflowPlan(
                1,
                10,
                List.of(new WorkflowStep(
                        "step-1",
                        "search",
                        "fact-1",
                        "secret claim",
                        List.of(new WorkflowEvidence("chunk-1", "fact-1", "secret evidence"))
                ))
        );
        WorkflowExecutionResult result = WorkflowExecutionResult.completed(List.of(
                new WorkflowStepExecution("step-1", WorkflowStepExecutionStatus.SUCCEEDED, "private tool output")
        ));

        recorder.record("trace-1", 7L, "private user request", plan, result, 42L);

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var argsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(sqlCaptor.getValue()).contains("workflow_execution_audit");
        assertThat(args).hasSize(11);
        assertThat(args[1]).isEqualTo("trace-1");
        assertThat(args[2]).isEqualTo(7L);
        assertThat((String) args[3]).isNotEqualTo("private user request").matches("[0-9a-f]{64}");
        assertThat((String) args[4]).matches("[0-9a-f]{64}");
        assertThat(args[5]).isEqualTo("SUCCEEDED");
        assertThat(args[8]).isEqualTo(42L);
        assertThat(args[9]).isEqualTo(LocalDateTime.of(2026, 10, 21, 0, 0));
        assertThat(args[10]).isEqualTo(LocalDateTime.of(2027, 1, 19, 0, 0));

        JsonNode metadata = new ObjectMapper().readTree((String) args[6]);
        assertThat(metadata.get(0).path("stepId").asText()).isEqualTo("step-1");
        assertThat(metadata.get(0).path("toolName").asText()).isEqualTo("search");
        assertThat(metadata.get(0).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(metadata.get(0).path("outputSha256").asText()).matches("[0-9a-f]{64}");
        assertThat((String) args[6]).doesNotContain("secret claim", "secret evidence", "private tool output");
        assertThat(args[7]).isNull();
    }
}
