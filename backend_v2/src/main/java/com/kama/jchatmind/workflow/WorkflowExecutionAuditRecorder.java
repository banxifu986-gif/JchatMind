package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class WorkflowExecutionAuditRecorder {

    private static final String INSERT_SQL = """
            INSERT INTO workflow_execution_audit (
                audit_id, trace_id, owner_id, request_sha256, plan_sha256, outcome,
                step_metadata, failure_code, duration_ms, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public WorkflowExecutionAuditRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, Clock.systemUTC());
    }

    WorkflowExecutionAuditRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void record(
            String traceId,
            Long ownerId,
            String requestText,
            WorkflowPlan plan,
            WorkflowExecutionResult result,
            long durationMs
    ) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        String outcome = outcome(result);
        jdbcTemplate.update(
                INSERT_SQL,
                UUID.randomUUID(),
                traceId,
                ownerId,
                sha256(requestText),
                plan == null ? null : sha256(writeJson(plan)),
                outcome,
                writeJson(stepMetadata(plan, result)),
                failureCode(outcome),
                durationMs,
                createdAt,
                createdAt.plusDays(90)
        );
    }

    public void recordFallback(
            String traceId,
            Long ownerId,
            String requestText,
            String fallbackResponse,
            long durationMs
    ) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        String metadata = writeJson(List.of(Map.of(
                "stepId", "single-agent-fallback",
                "toolName", "single-agent-fallback",
                "status", fallbackResponse == null ? "FAILED" : "SUCCEEDED",
                "outputSha256", sha256(fallbackResponse)
        )));
        String outcome = fallbackResponse == null ? "FALLBACK_FAILED" : "FALLBACK_SUCCEEDED";
        jdbcTemplate.update(
                INSERT_SQL,
                UUID.randomUUID(),
                traceId,
                ownerId,
                sha256(requestText),
                null,
                outcome,
                metadata,
                "FALLBACK_SUCCEEDED".equals(outcome) ? null : "WORKFLOW_FALLBACK_FAILED",
                durationMs,
                createdAt,
                createdAt.plusDays(90)
        );
    }

    private List<Map<String, Object>> stepMetadata(WorkflowPlan plan, WorkflowExecutionResult result) {
        Map<String, String> toolNames = new LinkedHashMap<>();
        if (plan != null && plan.steps() != null) {
            plan.steps().forEach(step -> toolNames.put(step.id(), step.toolName()));
        }
        List<Map<String, Object>> metadata = new ArrayList<>();
        if (result.steps() == null) {
            return metadata;
        }
        for (WorkflowStepExecution step : result.steps()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepId", step.stepId());
            item.put("toolName", toolNames.get(step.stepId()));
            item.put("status", step.status().name());
            item.put("outputSha256", sha256(step.output()));
            metadata.add(item);
        }
        return metadata;
    }

    private String outcome(WorkflowExecutionResult result) {
        if (result.completed()) {
            return "SUCCEEDED";
        }
        if (result.steps() != null && result.steps().stream()
                .anyMatch(step -> step.status() == WorkflowStepExecutionStatus.TIMED_OUT)) {
            return "TIMED_OUT";
        }
        if (result.violations() != null && !result.violations().isEmpty()) {
            return "REJECTED";
        }
        return "FAILED";
    }

    private String failureCode(String outcome) {
        return "SUCCEEDED".equals(outcome) ? null : "WORKFLOW_" + outcome;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成工作流审计元数据", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
