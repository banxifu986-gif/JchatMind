package com.kama.jchatmind.agent.harness;

import com.kama.jchatmind.intent.IntentDecisionMetadata;

/**
 * run 级工具执行元数据，经 ToolContext 显式传递至 HarnessToolCallbackProxy。
 */
public record HarnessRunMetadata(
        String userId,
        String agentId,
        String sessionId,
        int stepNumber,
        IntentDecisionMetadata intentDecisionMetadata
) {
}
