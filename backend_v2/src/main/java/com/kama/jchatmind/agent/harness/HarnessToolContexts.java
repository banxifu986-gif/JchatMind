package com.kama.jchatmind.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 工具调用 ToolContext 的唯一组装点：run 元数据 + per-call 上下文队列。
 * 签名规则（toolName|toolInput）与键名统一由本类管理，消费方不得自行拼装。
 */
public final class HarnessToolContexts {

    /** 签名：同一 run 内同工具同入参的多次调用按序消费 per-call 上下文。 */
    public static String signature(String toolName, String toolInput) {
        return toolName + "|" + toolInput;
    }

    /** 组装显式传递给 HarnessToolCallbackProxy 的 ToolContext 数据。
     *  队列为并发结构：P3 并行编排下同一签名的队列可能被多工作线程同时 poll。 */
    public static Map<String, Object> toContextData(HarnessRunMetadata metadata, List<HarnessContext> contexts) {
        Map<String, Queue<HarnessContext>> signatureToQueues = new ConcurrentHashMap<>();
        for (HarnessContext context : contexts) {
            signatureToQueues
                    .computeIfAbsent(signature(context.getToolName(), context.getToolInput()), key -> new ConcurrentLinkedQueue<>())
                    .add(context);
        }
        Map<String, Object> harnessData = new ConcurrentHashMap<>();
        harnessData.put(HarnessConstants.TOOL_CONTEXT_RUN_METADATA, metadata);
        harnessData.put(HarnessConstants.TOOL_CONTEXT_CALL_CONTEXTS, signatureToQueues);
        return harnessData;
    }

    private HarnessToolContexts() {
    }
}
