package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class ChatEventListener {

    private final JChatMindFactory jChatMindFactory;
    private final UserMemoryFacadeService userMemoryFacadeService;
    private final ChatSessionExecutionCoordinator chatSessionExecutionCoordinator;
    private final MemoryExtractionFailureRegistry memoryExtractionFailureRegistry;
    private final Executor agentTaskExecutor;
    private final AtomicLong rejectedTaskCount = new AtomicLong();

    public ChatEventListener(
            JChatMindFactory jChatMindFactory,
            UserMemoryFacadeService userMemoryFacadeService,
            ChatSessionExecutionCoordinator chatSessionExecutionCoordinator,
            MemoryExtractionFailureRegistry memoryExtractionFailureRegistry,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor
    ) {
        this.jChatMindFactory = jChatMindFactory;
        this.userMemoryFacadeService = userMemoryFacadeService;
        this.chatSessionExecutionCoordinator = chatSessionExecutionCoordinator;
        this.memoryExtractionFailureRegistry = memoryExtractionFailureRegistry;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    @EventListener
    public void handle(ChatEvent event) {
        ChatSessionExecutionCoordinator.ExecutionTicket ticket =
                chatSessionExecutionCoordinator.reserve(event.getSessionId());
        Runnable task = () -> chatSessionExecutionCoordinator.execute(
                ticket,
                () -> handleInSession(event)
        );
        try {
            agentTaskExecutor.execute(task);
        } catch (TaskRejectedException firstRejection) {
            recordRejection();
            try {
                agentTaskExecutor.execute(task);
            } catch (TaskRejectedException secondRejection) {
                recordRejection();
                chatSessionExecutionCoordinator.cancel(ticket);
                throw secondRejection;
            } catch (RuntimeException exception) {
                chatSessionExecutionCoordinator.cancel(ticket);
                throw exception;
            }
        } catch (RuntimeException exception) {
            chatSessionExecutionCoordinator.cancel(ticket);
            throw exception;
        }
    }

    private void recordRejection() {
        long rejectionCount = rejectedTaskCount.incrementAndGet();
        log.warn("Agent task rejected by bounded executor; process rejection count={}", rejectionCount);
    }

    public long getRejectedTaskCount() {
        return rejectedTaskCount.get();
    }

    public ExecutorSnapshot getExecutorSnapshot() {
        if (!(agentTaskExecutor instanceof ThreadPoolTaskExecutor threadPoolTaskExecutor)) {
            throw new IllegalStateException("Agent task executor does not expose pool metrics");
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

    private void handleInSession(ChatEvent event) {
        try {
            JChatMind jChatMind = jChatMindFactory.create(
                    event.getUserId(),
                    event.getAgentId(),
                    event.getSessionId()
            );
            jChatMind.run();
        } finally {
            try {
                MemoryExtractionResult extractionResult = userMemoryFacadeService.extractMemoryCandidates(
                        event.getUserId(),
                        event.getSessionId()
                );
                if (extractionResult == MemoryExtractionResult.EXTRACTED) {
                    memoryExtractionFailureRegistry.clear(event.getUserId(), event.getSessionId());
                }
            } catch (Exception e) {
                memoryExtractionFailureRegistry.recordFailure(event.getUserId(), event.getSessionId(), e);
                log.warn("Failed to extract memory candidates for session {}: {}",
                        event.getSessionId(), e.getClass().getName());
            }
        }
    }
}
