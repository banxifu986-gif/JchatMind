package com.kama.jchatmind.agent;

import jakarta.annotation.PreDestroy;
import com.kama.jchatmind.agent.loop.AgentCancelState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentToolExecutor {

    private static final ThreadLocal<Cancellation> CURRENT = new ThreadLocal<>();
    private static final int MAX_CONCURRENT_TOOL_CALLS = 4;
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
            Duration timeout,
            com.kama.jchatmind.agent.loop.AgentCancelState cancelState
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
        // 超时 / 用户取消先到先断（契约 §5）：分片等待，任一触发即取消在飞任务
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                long slice = Math.min(remaining, 50_000_000L);
                try {
                    return task.get(slice, TimeUnit.NANOSECONDS);
                } catch (TimeoutException sliceTimeout) {
                    if (cancelState != null && cancelState.isCancelled()) {
                        cancellation.cancelled = true;
                        task.cancel(true);
                        throw new CancellationException("Tool execution cancelled by user");
                    }
                    if (remaining <= 0) {
                        cancellation.cancelled = true;
                        task.cancel(true);
                        throw new TimeoutException("Tool execution timed out");
                    }
                }
            }
        } catch (InterruptedException | ExecutionException | TimeoutException exception) {
            cancellation.cancelled = true;
            task.cancel(true);
            throw exception;
        } finally {
            active.remove(cancellation);
        }
    }

    /**
     * 单轮多工具调用并行编排（契约 §6）：无序发起、结果按 toolCall 声明顺序回填、
     * 失败隔离（单 call 异常产错误 result，其余正常完成）、aborted 检查前置。
     * 并发上限 = 执行器线程池容量（4，R3.2），池满时延后提交。
     * 批级超时/用户取消沿用既有语义：中断全部在飞调用并抛出（调用方按"结果未确认"收口）。
     */
    public ToolResponseMessage executeParallel(
            ToolCallingManager manager,
            Prompt prompt,
            ChatResponse batchResponse,
            List<AssistantMessage.ToolCall> orderedCalls,
            Duration timeout,
            AgentCancelState cancelState
    ) throws InterruptedException, TimeoutException {
        if (orderedCalls.isEmpty()) {
            return ToolResponseMessage.builder().responses(List.of()).build();
        }

        ExecutorCompletionService<ToolResponseMessage> completion = new ExecutorCompletionService<>(executor);
        Cancellation cancellation = new Cancellation();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        int total = orderedCalls.size();

        Map<Integer, java.util.concurrent.Future<ToolResponseMessage>> futures = new HashMap<>();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            responses.add(null);
        }
        int submitted = 0;
        int collected = 0;
        int inFlight = 0;
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        synchronized (executor) {
            active.add(cancellation);
        }
        try {
            while (collected < total) {
                if (cancelState != null && cancelState.isCancelled()) {
                    cancelInFlight(cancellation, futures);
                    throw new CancellationException("Tool execution cancelled by user");
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    cancelInFlight(cancellation, futures);
                    throw new TimeoutException("Tool execution timed out");
                }

                // 尽量提交（并发上限 = 池容量；池满延后，等一次完成腾出线程再试）
                while (submitted < total && inFlight < MAX_CONCURRENT_TOOL_CALLS) {
                    AssistantMessage.ToolCall toolCall = orderedCalls.get(submitted);
                    int index = submitted;
                    try {
                        futures.put(index, completion.submit(singleCallTask(
                                manager, prompt, batchResponse, toolCall, cancellation, attributes)));
                        submitted++;
                        inFlight++;
                    } catch (RejectedExecutionException poolFull) {
                        break;
                    }
                }

                long pollSlice = Math.min(remaining, 25_000_000L);
                java.util.concurrent.Future<ToolResponseMessage> done =
                        completion.poll(Math.max(pollSlice, 1_000_000L), TimeUnit.NANOSECONDS);
                if (done == null) {
                    continue;
                }
                Integer index = futures.entrySet().stream()
                        .filter(entry -> entry.getValue() == done)
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseThrow();
                inFlight--;
                try {
                    ToolResponseMessage message = done.get();
                    responses.set(index, message.getResponses().get(0));
                } catch (java.util.concurrent.ExecutionException executionException) {
                    if (cancelState != null && cancelState.isCancelled()) {
                        // 取消引发的失败交由循环头的取消检查统一收口
                        continue;
                    }
                    responses.set(index, new ToolResponseMessage.ToolResponse(
                            orderedCalls.get(index).id(),
                            orderedCalls.get(index).name(),
                            "错误：工具执行失败"
                    ));
                }
                collected++;
            }
        } finally {
            synchronized (executor) {
                active.remove(cancellation);
            }
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private void cancelInFlight(
            Cancellation cancellation,
            Map<Integer, java.util.concurrent.Future<ToolResponseMessage>> futures
    ) {
        // 与串行路径一致：置位批级取消标志 + 中断，checkActive 在吞中断的工具上依然可靠（契约 §6 aborted 前置）
        cancellation.cancelled = true;
        for (java.util.concurrent.Future<ToolResponseMessage> future : futures.values()) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private java.util.concurrent.Callable<ToolResponseMessage> singleCallTask(
            ToolCallingManager manager,
            Prompt prompt,
            ChatResponse batchResponse,
            AssistantMessage.ToolCall toolCall,
            Cancellation cancellation,
            RequestAttributes attributes
    ) {
        return () -> {
            CURRENT.set(cancellation);
            RequestContextHolder.setRequestAttributes(attributes);
            try {
                checkActive();
                AssistantMessage singleOutput = AssistantMessage.builder()
                        .content(batchResponse.getResult().getOutput().getText())
                        .media(batchResponse.getResult().getOutput().getMedia())
                        .properties(batchResponse.getResult().getOutput().getMetadata())
                        .toolCalls(List.of(toolCall))
                        .build();
                ChatResponse singleResponse = new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(singleOutput)));
                var result = manager.executeToolCalls(prompt, singleResponse);
                checkActive();
                var history = result.conversationHistory();
                return (ToolResponseMessage) history.get(history.size() - 1);
            } catch (RuntimeException exception) {
                checkActive();
                throw exception;
            } finally {
                RequestContextHolder.resetRequestAttributes();
                CURRENT.remove();
            }
        };
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
