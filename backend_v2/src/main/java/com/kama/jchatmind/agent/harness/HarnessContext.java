package com.kama.jchatmind.agent.harness;

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

    @Builder.Default
    private Map<String, Object> attributes = new ConcurrentHashMap<>();
}
