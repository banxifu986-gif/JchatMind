package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.proxy.HarnessExecutionContextHolder;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentToolExecutor {

    private static final ThreadLocal<Cancellation> CURRENT = new ThreadLocal<>();
    private final ThreadPoolExecutor executor;
    private final Set<Cancellation> active = ConcurrentHashMap.newKeySet();

    public AgentToolExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "agent-tool-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public ToolResponseMessage execute(
            ToolCallingManager manager,
            Prompt prompt,
            ChatResponse response,
            List<HarnessContext> contexts,
            HarnessExecutionContextHolder.BatchMetadata metadata,
            Duration timeout
    ) throws InterruptedException, ExecutionException, TimeoutException {
        Cancellation cancellation = new Cancellation();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Future<ToolResponseMessage> task;
        synchronized (executor) {
            active.add(cancellation);
            try {
                task = executor.submit(() -> {
                    CURRENT.set(cancellation);
                    RequestContextHolder.setRequestAttributes(attributes);
                    HarnessExecutionContextHolder.bind(contexts, metadata);
                    try {
                        checkActive();
                        var result = manager.executeToolCalls(prompt, response);
                        checkActive();
                        var history = result.conversationHistory();
                        return (ToolResponseMessage) history.get(history.size() - 1);
                    } catch (RuntimeException exception) {
                        checkActive();
                        throw exception;
                    } finally {
                        HarnessExecutionContextHolder.clear();
                        RequestContextHolder.resetRequestAttributes();
                        CURRENT.remove();
                        active.remove(cancellation);
                    }
                });
            } catch (RuntimeException exception) {
                active.remove(cancellation);
                throw exception;
            }
        }
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException exception) {
            cancellation.cancelled = true;
            task.cancel(true);
            throw exception;
        } finally {
            active.remove(cancellation);
        }
    }

    public static void checkActive() {
        Cancellation cancellation = CURRENT.get();
        if (cancellation != null && (cancellation.cancelled || Thread.currentThread().isInterrupted())) {
            throw new CancellationException("Tool execution batch cancelled");
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (executor) {
            active.forEach(cancellation -> cancellation.cancelled = true);
            executor.shutdownNow();
        }
    }

    private static class Cancellation {
        private volatile boolean cancelled;
    }
}
