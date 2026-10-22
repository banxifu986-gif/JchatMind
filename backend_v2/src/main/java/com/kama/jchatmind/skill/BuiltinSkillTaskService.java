package com.kama.jchatmind.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.BuiltinSkillTaskMapper;
import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@AllArgsConstructor
public class BuiltinSkillTaskService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_ATTEMPTS = 5;

    private final BuiltinSkillExecutionService executionService;
    private final BuiltinSkillTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public SubmissionResult submit(
            Long requesterUserId,
            String agentId,
            String sessionId,
            String skillId,
            String idempotencyKey,
            Map<String, Object> input
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        BuiltinSkillExecutionService.PreparedSkillExecution prepared = executionService.prepare(
                requesterUserId, agentId, sessionId, skillId, input
        );
        Map<String, Object> inputSnapshot = inputSnapshot(prepared.invocation());
        String inputSnapshotJson = writeJson(inputSnapshot);
        String requestSha256 = requestSha256(prepared, inputSnapshot);

        BuiltinSkillExecutionTask task = new BuiltinSkillExecutionTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setRequesterUserId(requesterUserId);
        task.setTaskType("BUILTIN_SKILL");
        task.setAgentId(prepared.agentId());
        task.setSessionId(prepared.sessionId());
        task.setSkillId(prepared.skillId());
        task.setSkillVersion(prepared.invocation().definition().version());
        task.setIdempotencyKey(normalizedKey);
        task.setRequestSha256(requestSha256);
        task.setInputSnapshot(inputSnapshotJson);
        task.setStatus("PENDING");
        task.setProgress((short) 0);
        task.setAttemptCount(0);
        task.setMaxAttempts(MAX_ATTEMPTS);
        task.setDispatchAttemptCount(0);
        task.setResultReference("/api/skill-tasks/" + task.getTaskId());

        if (taskMapper.insertIfAbsent(task) == 1) {
            return submissionResult(task, false);
        }

        BuiltinSkillExecutionTask existing = taskMapper.selectByRequesterAndIdempotencyKey(
                requesterUserId, normalizedKey
        );
        if (existing == null) {
            throw new IllegalStateException("Skill task idempotency conflict row is unavailable");
        }
        if (!requestSha256.equals(existing.getRequestSha256())) {
            throw new BizException("幂等键已用于不同任务请求");
        }
        return submissionResult(existing, true);
    }

    @Transactional(readOnly = true)
    public BuiltinSkillExecutionTask getOwnedTask(String taskId, Long requesterUserId) {
        if (!StringUtils.hasText(taskId) || requesterUserId == null || requesterUserId <= 0) {
            throw new BizException("任务不存在或无权访问");
        }
        BuiltinSkillExecutionTask task = taskMapper.selectByIdAndRequester(taskId, requesterUserId);
        if (task == null) {
            throw new BizException("任务不存在或无权访问");
        }
        return task;
    }

    private SubmissionResult submissionResult(BuiltinSkillExecutionTask task, boolean replayed) {
        return new SubmissionResult(task.getTaskId(), task.getStatus(), task.getResultReference(), replayed);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BizException("Idempotency-Key 无效");
        }
        return idempotencyKey.trim();
    }

    private Map<String, Object> inputSnapshot(PreparedSkillInvocation invocation) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("question", invocation.input().get("question"));
        snapshot.put("kbIds", invocation.knowledgeBaseIds().stream().sorted().toList());
        return snapshot;
    }

    private String requestSha256(
            BuiltinSkillExecutionService.PreparedSkillExecution prepared,
            Map<String, Object> inputSnapshot
    ) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("requesterUserId", prepared.userId());
        fingerprint.put("agentId", prepared.agentId());
        fingerprint.put("sessionId", prepared.sessionId());
        fingerprint.put("skillId", prepared.skillId());
        fingerprint.put("skillVersion", prepared.invocation().definition().version());
        fingerprint.put("input", inputSnapshot);
        return sha256(writeJson(fingerprint));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法冻结 Skill task input", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record SubmissionResult(String taskId, String status, String resultReference, boolean replayed) {
    }
}
