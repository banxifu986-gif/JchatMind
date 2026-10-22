package com.kama.jchatmind.event.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ChatSessionExecutionCoordinator {

    private final AgentExecutionSessionLockManager sessionLockManager;
    private final ConcurrentMap<String, SessionExecution> sessionExecutions = new ConcurrentHashMap<>();

    public ChatSessionExecutionCoordinator() {
        this(null);
    }

    @Autowired
    public ChatSessionExecutionCoordinator(AgentExecutionSessionLockManager sessionLockManager) {
        this.sessionLockManager = sessionLockManager;
    }

    public ExecutionTicket reserve(String sessionId) {
        Assert.hasText(sessionId, "Chat session id cannot be empty");
        AtomicReference<ExecutionTicket> reserved = new AtomicReference<>();
        sessionExecutions.compute(sessionId, (ignored, existing) -> {
            SessionExecution execution = existing == null ? new SessionExecution() : existing;
            reserved.set(new ExecutionTicket(this, sessionId, execution, execution.reserve()));
            return execution;
        });
        return reserved.get();
    }

    public void execute(String sessionId, Runnable task) {
        Assert.notNull(task, "Chat session task cannot be null");
        execute(reserve(sessionId), task);
    }

    public void execute(ExecutionTicket ticket, Runnable task) {
        Assert.notNull(ticket, "Chat session execution ticket cannot be null");
        Assert.notNull(task, "Chat session task cannot be null");
        if (ticket.owner != this || !ticket.claimed.compareAndSet(false, true)) {
            throw new IllegalStateException("Chat session execution ticket is invalid or already used");
        }

        boolean entered = false;
        try {
            entered = ticket.execution.awaitTurn(ticket.order);
            if (entered) {
                if (sessionLockManager == null) {
                    task.run();
                } else {
                    try (AgentExecutionSessionLockManager.SessionLock ignored =
                                 sessionLockManager.lock(ticket.sessionId)) {
                        task.run();
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for chat session execution order", exception);
        } finally {
            if (entered) {
                ticket.execution.complete(ticket.order);
            } else {
                ticket.execution.cancel(ticket.order);
            }
            release(ticket);
        }
    }

    public void cancel(ExecutionTicket ticket) {
        Assert.notNull(ticket, "Chat session execution ticket cannot be null");
        if (ticket.owner != this) {
            throw new IllegalArgumentException("Chat session execution ticket belongs to another coordinator");
        }
        if (ticket.claimed.compareAndSet(false, true)) {
            ticket.execution.cancel(ticket.order);
            release(ticket);
        }
    }

    private void release(ExecutionTicket ticket) {
        if (ticket.released.compareAndSet(false, true)) {
            sessionExecutions.computeIfPresent(ticket.sessionId, (ignored, execution) -> {
                if (execution != ticket.execution) {
                    return execution;
                }
                return execution.release() == 0 ? null : execution;
            });
        }
    }

    public static final class ExecutionTicket {

        private final ChatSessionExecutionCoordinator owner;
        private final String sessionId;
        private final SessionExecution execution;
        private final long order;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private ExecutionTicket(
                ChatSessionExecutionCoordinator owner,
                String sessionId,
                SessionExecution execution,
                long order
        ) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.execution = execution;
            this.order = order;
        }
    }

    private static class SessionExecution {

        private final Set<Long> cancelledOrders = new HashSet<>();
        private long nextOrder;
        private long nextExecution;
        private int references;

        private synchronized long reserve() {
            references++;
            return nextOrder++;
        }

        private synchronized boolean awaitTurn(long order) throws InterruptedException {
            while (order != nextExecution && !cancelledOrders.contains(order)) {
                wait();
            }
            return !cancelledOrders.contains(order);
        }

        private synchronized void complete(long order) {
            if (order != nextExecution) {
                throw new IllegalStateException("Chat session execution completed out of order");
            }
            nextExecution++;
            advanceCancelledOrders();
            notifyAll();
        }

        private synchronized void cancel(long order) {
            if (order < nextExecution) {
                return;
            }
            cancelledOrders.add(order);
            advanceCancelledOrders();
            notifyAll();
        }

        private void advanceCancelledOrders() {
            while (cancelledOrders.remove(nextExecution)) {
                nextExecution++;
            }
        }

        private synchronized int release() {
            return --references;
        }
    }
}
