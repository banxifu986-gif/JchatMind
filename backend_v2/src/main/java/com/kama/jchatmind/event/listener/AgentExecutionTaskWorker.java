package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.model.dto.AgentExecutionTask;
import com.kama.jchatmind.service.AgentExecutionTaskQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "agent.execution.queue", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentExecutionTaskWorker {

    private static final int CLAIM_CANDIDATE_LIMIT = 128;

    private final AgentExecutionTaskQueue agentExecutionTaskQueue;
    private final AgentExecutionSessionLockManager sessionLockManager;
    private final AgentExecutionTaskRunner agentExecutionTaskRunner;
    private final Executor agentExecutionWorkerExecutor;
    private final AtomicLong rejectedTaskCount = new AtomicLong();

    public AgentExecutionTaskWorker(
            AgentExecutionTaskQueue agentExecutionTaskQueue,
            AgentExecutionSessionLockManager sessionLockManager,
            AgentExecutionTaskRunner agentExecutionTaskRunner,
            @Qualifier("agentExecutionWorkerExecutor") Executor agentExecutionWorkerExecutor
    ) {
        this.agentExecutionTaskQueue = agentExecutionTaskQueue;
        this.sessionLockManager = sessionLockManager;
        this.agentExecutionTaskRunner = agentExecutionTaskRunner;
        this.agentExecutionWorkerExecutor = agentExecutionWorkerExecutor;
    }

    @Scheduled(fixedDelay = 250)
    public void dispatchPendingTasks() {
        try {
            if (isSaturated()) {
                return;
            }
            List<AgentExecutionTask> heads = agentExecutionTaskQueue
                    .findRunnableSessionHeads(CLAIM_CANDIDATE_LIMIT);
            for (AgentExecutionTask head : heads) {
                if (!dispatch(head)) {
                    break;
                }
            }
        } catch (Exception exception) {
            log.error("Agent execution queue poll failed: {}", exception.getClass().getName());
        }
    }

    private boolean dispatch(AgentExecutionTask head) {
        AgentExecutionSessionLockManager.SessionLock sessionLock = sessionLockManager.tryLock(head.getSessionId());
        if (sessionLock == null) {
            return true;
        }

        AgentExecutionTask task;
        try {
            task = agentExecutionTaskQueue.claim(head.getId());
            if (task == null) {
                closeLock(sessionLock);
                return true;
            }
            agentExecutionWorkerExecutor.execute(() -> execute(task, sessionLock));
            return true;
        } catch (TaskRejectedException rejectedException) {
            long count = rejectedTaskCount.incrementAndGet();
            log.warn("Durable agent task rejected by bounded worker; process rejection count={}", count);
            releaseClaim(head.getId(), sessionLock);
            return false;
        } catch (RuntimeException exception) {
            releaseClaim(head.getId(), sessionLock);
            log.error("Agent execution task dispatch failed: {}", exception.getClass().getName());
            return false;
        }
    }

    private boolean isSaturated() {
        if (!(agentExecutionWorkerExecutor instanceof ThreadPoolTaskExecutor threadPoolTaskExecutor)) {
            return false;
        }
        var executor = threadPoolTaskExecutor.getThreadPoolExecutor();
        return executor.getActiveCount() >= executor.getMaximumPoolSize();
    }

    public ExecutorSnapshot getExecutorSnapshot() {
        if (!(agentExecutionWorkerExecutor instanceof ThreadPoolTaskExecutor threadPoolTaskExecutor)) {
            throw new IllegalStateException("Agent execution worker does not expose pool metrics");
        }
        var executor = threadPoolTaskExecutor.getThreadPoolExecutor();
        return new ExecutorSnapshot(
                rejectedTaskCount.get(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity()
        );
    }

    public record ExecutorSnapshot(
            long rejectedTaskCount,
            int poolSize,
            int activeThreads,
            int queuedTasks,
            int remainingQueueCapacity
    ) {
    }

    private void execute(AgentExecutionTask task, AgentExecutionSessionLockManager.SessionLock sessionLock) {
        try {
            try {
                AgentExecutionTurnContext.run(task.getSequenceNo(), () -> agentExecutionTaskRunner.runPersistedTask(
                        new ChatEvent(
                                task.getUserId(),
                                task.getAgentId(),
                                task.getSessionId(),
                                task.getUserMessageId(),
                                task.getSequenceNo(),
                                null
                        )
                ));
            } catch (Exception exception) {
                try {
                    agentExecutionTaskQueue.fail(task.getId(), exception.getClass().getName());
                } catch (RuntimeException stateException) {
                    log.error("Agent execution task failure state could not be persisted: {}",
                            stateException.getClass().getName());
                }
                log.error("Agent execution task failed: {}", exception.getClass().getName());
                return;
            }

            try {
                agentExecutionTaskQueue.complete(task.getId());
            } catch (RuntimeException exception) {
                log.error("Agent execution task completion could not be persisted: {}",
                        exception.getClass().getName());
            }
        } finally {
            closeLock(sessionLock);
        }
    }

    private void releaseClaim(
            String taskId,
            AgentExecutionSessionLockManager.SessionLock sessionLock
    ) {
        try {
            agentExecutionTaskQueue.release(taskId);
        } catch (RuntimeException exception) {
            log.error("Agent execution task could not be released: {}", exception.getClass().getName());
        } finally {
            closeLock(sessionLock);
        }
    }

    private void closeLock(AgentExecutionSessionLockManager.SessionLock sessionLock) {
        try {
            sessionLock.close();
        } catch (RuntimeException exception) {
            log.error("Agent execution session lock could not be released: {}", exception.getClass().getName());
        }
    }
}
