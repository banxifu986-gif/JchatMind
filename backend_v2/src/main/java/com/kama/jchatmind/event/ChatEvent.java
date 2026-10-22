package com.kama.jchatmind.event;

import lombok.Data;

@Data
public class ChatEvent {
    private String userId;
    private String agentId;
    private String sessionId;
    private String userMessageId;
    private Long sequenceNo;
    private String userInput;

    public ChatEvent(
            String userId,
            String agentId,
            String sessionId,
            String userMessageId,
            Long sequenceNo,
            String userInput
    ) {
        this.userId = userId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
        this.sequenceNo = sequenceNo;
        this.userInput = userInput;
    }
}
