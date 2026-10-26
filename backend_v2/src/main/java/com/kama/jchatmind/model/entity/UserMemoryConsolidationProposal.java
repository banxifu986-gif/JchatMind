package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserMemoryConsolidationProposal {
    private String id;
    private String userId;
    private String sourceMemoryIdsJson;
    private String sourceFingerprint;
    private String proposedType;
    private String proposedContent;
    private String status;
    private String appliedMemoryId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
