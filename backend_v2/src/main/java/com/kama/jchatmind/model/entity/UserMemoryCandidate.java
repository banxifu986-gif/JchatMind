package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserMemoryCandidate {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PERSISTED = "PERSISTED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    private String id;
    private String userId;
    private String sessionId;
    private String memoryType;
    private String content;
    private String evidence;
    private String importance;
    private String evidenceMessageId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
