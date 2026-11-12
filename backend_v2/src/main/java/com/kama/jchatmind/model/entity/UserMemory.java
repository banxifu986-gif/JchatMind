package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMemory {
    private String id;
    private String userId;
    private String sessionId;
    private String memoryType;
    private String content;
    private String importance;
    private String evidenceMessageId;
    private String evidenceText;
    private String supersededByMemoryId;
    private LocalDateTime expiresAt;
    private float[] embedding;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
