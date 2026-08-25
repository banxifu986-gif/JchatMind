package com.kama.jchatmind.event.listener;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ChatSessionExecutionCoordinator {

    private final ConcurrentMap<String, SessionExecution> sessionExecutions = new ConcurrentHashMap<>();

    public void execute(String sessionId, Runnable task) {
        Assert.hasText(sessionId, "Chat session id cannot be empty");
        Assert.notNull(task, "Chat session task cannot be null");

        SessionExecution execution = sessionExecutions.compute(sessionId, (ignored, existing) -> {
            SessionExecution retained = existing == null ? new SessionExecution() : existing;
            retained.retain();
            return retained;
        });
        execution.lock();
        try {
            task.run();
        } finally {
            execution.unlock();
            sessionExecutions.computeIfPresent(sessionId, (ignored, existing) -> {
                if (existing != execution) {
                    return existing;
                }
                return execution.release() == 0 ? null : execution;
            });
        }
    }

    private static class SessionExecution {

        private final ReentrantLock lock = new ReentrantLock();
        private int references;

        private void retain() {
            references++;
        }

        private int release() {
            return --references;
        }

        private void lock() {
            lock.lock();
        }

        private void unlock() {
            lock.unlock();
        }
    }
}
