package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.deletion.KnowledgeBaseDeletionTaskPublisher;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseDeletionTaskMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionAuditRecord;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@AllArgsConstructor
public class KnowledgeBaseDeletionTaskServiceImpl {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final KnowledgeBaseDeletionTaskMapper deletionTaskMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final RequestScopeData requestScopeData;
    private final KnowledgeBaseDeletionTaskPublisher deletionTaskPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public KnowledgeBaseDeletionTask requestDeletion(String knowledgeBaseId) {
        Long ownerId = requireUserId();
        String idempotencyKey = deletionIdempotencyKey(knowledgeBaseId);
        deletionTaskMapper.lockOwnerIdempotencyKey(ownerId, idempotencyKey);
        KnowledgeBaseDeletionTask existingTask = deletionTaskMapper
                .selectByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existingTask != null) {
            return existingTask;
        }
        knowledgeBaseAccessService.requireAccessibleKnowledgeBase(knowledgeBaseId, String.valueOf(ownerId));

        LocalDateTime now = LocalDateTime.now();
        String taskId = UUID.randomUUID().toString();
        KnowledgeBaseDeletionTask task = KnowledgeBaseDeletionTask.builder()
                .id(taskId)
                .ownerId(ownerId)
                .knowledgeBaseId(knowledgeBaseId)
                .taskType("KNOWLEDGE_BASE_DELETION")
                .idempotencyKey(idempotencyKey)
                .inputSnapshot(toInputSnapshot(knowledgeBaseId))
                .status("QUEUED")
                .progress(0)
                .attemptCount(0)
                .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                .resultRef("knowledge-base-deletion-task:" + taskId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        if (deletionTaskMapper.insert(task) <= 0) {
            throw new BizException("创建知识库删除任务失败");
        }
        if (deletionTaskMapper.insertAudit(new KnowledgeBaseDeletionAuditRecord(
                UUID.randomUUID().toString(),
                task.getId(),
                ownerId,
                knowledgeBaseId,
                "DELETE_REQUESTED",
                task.getStatus(),
                now
        )) <= 0) {
            throw new BizException("写入知识库删除审计失败");
        }
        if (knowledgeBaseMapper.deleteByIdAndOwnerId(knowledgeBaseId, String.valueOf(ownerId)) <= 0) {
            throw new BizException("删除知识库失败");
        }

        publishAfterCommit(task.getId());
        return task;
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseDeletionTask getTask(String taskId) {
        KnowledgeBaseDeletionTask task = deletionTaskMapper.selectById(taskId);
        if (task == null || !requireUserId().equals(task.getOwnerId())) {
            throw new BizException("无权访问知识库删除任务");
        }
        return task;
    }

    @Transactional
    public KnowledgeBaseDeletionTask claimTask(String taskId) {
        KnowledgeBaseDeletionTask task = deletionTaskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }
        if ("RETRYING".equals(task.getStatus())) {
            if (!tryUpdateStatus(task, "RETRYING", "QUEUED", attemptCount(task), task.getErrorSummary())) {
                return null;
            }
        }
        if (!"QUEUED".equals(task.getStatus())) {
            return null;
        }
        if (!tryUpdateStatus(task, "QUEUED", "RUNNING", attemptCount(task), null)) {
            return null;
        }
        return task;
    }

    @Transactional
    public void completeClaimedTask(KnowledgeBaseDeletionTask task) {
        requireRunning(task);
        if (!tryUpdateStatus(task, "RUNNING", "SUCCEEDED", attemptCount(task), null)) {
            throw new BizException("知识库删除任务状态已变更，请重试");
        }
    }

    @Transactional
    public String failClaimedTask(KnowledgeBaseDeletionTask task, String errorSummary) {
        requireRunning(task);
        int nextAttemptCount = attemptCount(task) + 1;
        String nextStatus = nextAttemptCount >= maxAttempts(task) ? "DEAD_LETTER" : "RETRYING";
        if (!tryUpdateStatus(task, "RUNNING", nextStatus, nextAttemptCount, normalizeErrorSummary(errorSummary))) {
            throw new BizException("知识库删除任务状态已变更，请重试");
        }
        return nextStatus;
    }

    private boolean tryUpdateStatus(
            KnowledgeBaseDeletionTask task,
            String currentStatus,
            String nextStatus,
            int attemptCount,
            String errorSummary
    ) {
        if (deletionTaskMapper.updateStatusIfCurrent(
                task.getId(),
                currentStatus,
                nextStatus,
                attemptCount,
                errorSummary
        ) <= 0) {
            return false;
        }
        task.setStatus(nextStatus);
        task.setAttemptCount(attemptCount);
        task.setErrorSummary(errorSummary);
        return true;
    }

    private int attemptCount(KnowledgeBaseDeletionTask task) {
        return task.getAttemptCount() == null ? 0 : task.getAttemptCount();
    }

    private int maxAttempts(KnowledgeBaseDeletionTask task) {
        return task.getMaxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : task.getMaxAttempts();
    }

    private void requireRunning(KnowledgeBaseDeletionTask task) {
        if (task == null || !"RUNNING".equals(task.getStatus())) {
            throw new BizException("知识库删除任务状态非法");
        }
    }

    private String normalizeErrorSummary(String errorSummary) {
        if (!StringUtils.hasText(errorSummary)) {
            return "知识库文件清理失败";
        }
        return errorSummary.length() <= 500 ? errorSummary : errorSummary.substring(0, 500);
    }

    private void publishAfterCommit(String taskId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionTaskPublisher.publish(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletionTaskPublisher.publish(taskId);
            }
        });
    }

    private String toInputSnapshot(String knowledgeBaseId) {
        try {
            return objectMapper.writeValueAsString(Map.of("knowledgeBaseId", knowledgeBaseId));
        } catch (JsonProcessingException e) {
            throw new BizException("序列化知识库删除任务输入失败");
        }
    }

    private String deletionIdempotencyKey(String knowledgeBaseId) {
        return "KNOWLEDGE_BASE_DELETION:" + knowledgeBaseId;
    }

    private Long requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        return userId;
    }
}
