package com.kama.jchatmind.agent.harness;

import com.kama.jchatmind.intent.IntentDecisionMetadata;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
public class HarnessContext {
    private String sessionId;
    private String agentId;
    private String userId;
    private String toolCallId;
    private String toolName;
    private String toolInput;
    private int stepNumber;
    private IntentDecisionMetadata intentDecisionMetadata;

    @Builder.Default
    private Map<String, Object> attributes = new ConcurrentHashMap<>();
}
