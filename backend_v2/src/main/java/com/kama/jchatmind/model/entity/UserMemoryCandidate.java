package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserMemoryCandidate {
    private String id;
    private String userId;
    private String sessionId;
    private String memoryType;
    private String content;
    private String evidence;
    private String importance;
    private String evidenceMessageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
