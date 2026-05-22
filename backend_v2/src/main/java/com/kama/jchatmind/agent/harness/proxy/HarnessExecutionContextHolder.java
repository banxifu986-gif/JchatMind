package com.kama.jchatmind.agent.harness.proxy;

import com.kama.jchatmind.agent.harness.HarnessContext;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HarnessExecutionContextHolder {

    public record BatchMetadata(
            String sessionId,
            String agentId,
            String userId,
            int stepNumber
    ) {}

    private record BoundContexts(
            Map<String, ArrayDeque<HarnessContext>> groupedContexts,
            BatchMetadata metadata
    ) {}

    private static final ThreadLocal<BoundContexts> CONTEXTS = new ThreadLocal<>();

    private HarnessExecutionContextHolder() {
    }

    public static void bind(List<HarnessContext> contexts, BatchMetadata metadata) {
        Map<String, ArrayDeque<HarnessContext>> grouped = new HashMap<>();
        for (HarnessContext context : contexts) {
            grouped.computeIfAbsent(signature(context.getToolName(), context.getToolInput()), key -> new ArrayDeque<>())
                    .addLast(context);
        }
        CONTEXTS.set(new BoundContexts(grouped, metadata));
    }

    public static HarnessContext poll(String toolName, String toolInput) {
        BoundContexts boundContexts = CONTEXTS.get();
        if (boundContexts == null) {
            return null;
        }
        ArrayDeque<HarnessContext> queue = boundContexts.groupedContexts().get(signature(toolName, toolInput));
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        return queue.pollFirst();
    }

    public static BatchMetadata getMetadata() {
        BoundContexts boundContexts = CONTEXTS.get();
        return boundContexts == null ? null : boundContexts.metadata();
    }

    public static void clear() {
        CONTEXTS.remove();
    }

    private static String signature(String toolName, String toolInput) {
        return toolName + "|" + toolInput;
    }
}
