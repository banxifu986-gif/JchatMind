package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.ingestion.IngestionTaskPublisher;
import com.kama.jchatmind.ingestion.IngestionTaskStateMachine;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@AllArgsConstructor
public class IngestionTaskServiceImpl {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final IngestionTaskMapper ingestionTaskMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final RequestScopeData requestScopeData;
    private final IngestionTaskStateMachine stateMachine;
    private final IngestionTaskPublisher ingestionTaskPublisher;

    @Transactional
    public IngestionTask submitDocumentIngestion(String kbId, String documentId, String idempotencyKey) {
        Long ownerId = requireUserId();
        requireText(kbId, "知识库不能为空");
        requireText(documentId, "文档不能为空");
        requireText(idempotencyKey, "幂等键不能为空");

        ingestionTaskMapper.lockOwnerIdempotencyKey(ownerId, idempotencyKey);
        IngestionTask existing = ingestionTaskMapper.selectByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existing != null) {
            return validateIdempotentRequest(existing, kbId, documentId);
        }

        knowledgeBaseAccessService.requireAccessibleKnowledgeBase(kbId, String.valueOf(ownerId));
        Document document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(kbId, document.getKbId())) {
            throw new BizException("无权访问文档");
        }

        LocalDateTime now = LocalDateTime.now();
        IngestionTask task = IngestionTask.builder()
                .id(UUID.randomUUID().toString())
                .ownerId(ownerId)
                .kbId(kbId)
                .documentId(documentId)
                .idempotencyKey(idempotencyKey)
                .taskType("DOCUMENT_INGESTION")
                .status(IngestionTaskStateMachine.Status.QUEUED.name())
                .attemptCount(0)
                .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                .createdAt(now)
                .updatedAt(now)
                .build();

        if (ingestionTaskMapper.insert(task) == 0) {
            IngestionTask concurrentTask = ingestionTaskMapper.selectByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
            if (concurrentTask == null) {
                throw new BizException("任务创建冲突，请重试");
            }
            return validateIdempotentRequest(concurrentTask, kbId, documentId);
        }

        publishAfterCommit(task.getId());
        return task;
    }

    @Transactional(readOnly = true)
    public IngestionTask getTask(String taskId) {
        return requireOwnedTask(taskId);
    }

    @Transactional
    public IngestionTask findExistingDocumentIngestion(String kbId, String idempotencyKey) {
        Long ownerId = requireUserId();
        requireText(kbId, "知识库不能为空");
        requireText(idempotencyKey, "幂等键不能为空");
        ingestionTaskMapper.lockOwnerIdempotencyKey(ownerId, idempotencyKey);
        IngestionTask task = ingestionTaskMapper.selectByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (task == null) {
            return null;
        }
        if (!Objects.equals(task.getKbId(), kbId)) {
            throw new BizException("幂等键已用于其他资源");
        }
        return task;
    }

    @Transactional
    public void cancelTask(String taskId) {
        IngestionTask task = requireOwnedTask(taskId);
        IngestionTaskStateMachine.Status currentStatus = status(task);
        IngestionTaskStateMachine.Status nextStatus = stateMachine.transition(
                currentStatus,
                IngestionTaskStateMachine.Event.CANCEL
        );
        updateStatus(task, currentStatus, nextStatus, attemptCount(task), null);
    }

    @Transactional
    public void retryTask(String taskId) {
        IngestionTask task = requireOwnedTask(taskId);
        IngestionTaskStateMachine.Status currentStatus = status(task);
        IngestionTaskStateMachine.Status nextStatus = stateMachine.transition(
                currentStatus,
                IngestionTaskStateMachine.Event.MANUAL_RETRY
        );
        updateStatus(task, currentStatus, nextStatus, 0, null);
        publishAfterCommit(taskId);
    }

    @Transactional
    public IngestionTask claimTask(String taskId) {
        IngestionTask task = ingestionTaskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }

        IngestionTaskStateMachine.Status currentStatus = status(task);
        if (currentStatus == IngestionTaskStateMachine.Status.RETRYING) {
            IngestionTaskStateMachine.Status requeuedStatus = stateMachine.transition(
                    currentStatus,
                    IngestionTaskStateMachine.Event.REQUEUE
            );
            if (!tryUpdateStatus(task, currentStatus, requeuedStatus, attemptCount(task), task.getErrorSummary())) {
                return null;
            }
            currentStatus = requeuedStatus;
        }
        if (currentStatus != IngestionTaskStateMachine.Status.QUEUED) {
            return null;
        }

        IngestionTaskStateMachine.Status runningStatus = stateMachine.transition(
                currentStatus,
                IngestionTaskStateMachine.Event.START
        );
        if (!tryUpdateStatus(task, currentStatus, runningStatus, attemptCount(task), null)) {
            return null;
        }
        return task;
    }

    @Transactional
    public IngestionTaskStateMachine.Status failClaimedTask(IngestionTask task, String errorSummary) {
        IngestionTaskStateMachine.Status currentStatus = status(task);
        int nextAttemptCount = attemptCount(task) + 1;
        IngestionTaskStateMachine.Event event = nextAttemptCount >= maxAttempts(task)
                ? IngestionTaskStateMachine.Event.DEAD_LETTER
                : IngestionTaskStateMachine.Event.RETRY;
        IngestionTaskStateMachine.Status nextStatus = stateMachine.transition(currentStatus, event);
        updateStatus(task, currentStatus, nextStatus, nextAttemptCount, normalizeErrorSummary(errorSummary));
        return nextStatus;
    }

    @Transactional
    public void completeClaimedTask(IngestionTask task) {
        IngestionTaskStateMachine.Status currentStatus = status(task);
        IngestionTaskStateMachine.Status nextStatus = stateMachine.transition(
                currentStatus,
                IngestionTaskStateMachine.Event.SUCCEED
        );
        updateStatus(task, currentStatus, nextStatus, attemptCount(task), null);
    }

    private IngestionTask validateIdempotentRequest(IngestionTask existing, String kbId, String documentId) {
        if (!Objects.equals(existing.getKbId(), kbId) || !Objects.equals(existing.getDocumentId(), documentId)) {
            throw new BizException("幂等键已用于其他资源");
        }
        return existing;
    }

    private IngestionTask requireOwnedTask(String taskId) {
        IngestionTask task = ingestionTaskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getOwnerId(), requireUserId())) {
            throw new BizException("无权访问任务");
        }
        return task;
    }

    private IngestionTaskStateMachine.Status status(IngestionTask task) {
        try {
            return IngestionTaskStateMachine.Status.valueOf(task.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException("任务状态非法");
        }
    }

    private int attemptCount(IngestionTask task) {
        return task.getAttemptCount() == null ? 0 : task.getAttemptCount();
    }

    private int maxAttempts(IngestionTask task) {
        return task.getMaxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : task.getMaxAttempts();
    }

    private void updateStatus(
            IngestionTask task,
            IngestionTaskStateMachine.Status currentStatus,
            IngestionTaskStateMachine.Status nextStatus,
            int nextAttemptCount,
            String errorSummary
    ) {
        if (!tryUpdateStatus(task, currentStatus, nextStatus, nextAttemptCount, errorSummary)) {
            throw new BizException("任务状态已变更，请重试");
        }
    }

    private boolean tryUpdateStatus(
            IngestionTask task,
            IngestionTaskStateMachine.Status currentStatus,
            IngestionTaskStateMachine.Status nextStatus,
            int nextAttemptCount,
            String errorSummary
    ) {
        int updated = ingestionTaskMapper.updateStatusIfCurrent(
                task.getId(),
                currentStatus.name(),
                nextStatus.name(),
                nextAttemptCount,
                errorSummary
        );
        if (updated <= 0) {
            return false;
        }
        task.setStatus(nextStatus.name());
        task.setAttemptCount(nextAttemptCount);
        task.setErrorSummary(errorSummary);
        return true;
    }

    private void publishAfterCommit(String taskId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            ingestionTaskPublisher.publish(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ingestionTaskPublisher.publish(taskId);
            }
        });
    }

    private String normalizeErrorSummary(String errorSummary) {
        if (!StringUtils.hasText(errorSummary)) {
            return "摄入处理失败";
        }
        return errorSummary.length() <= 500 ? errorSummary : errorSummary.substring(0, 500);
    }

    private Long requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        return userId;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(message);
        }
    }
}
