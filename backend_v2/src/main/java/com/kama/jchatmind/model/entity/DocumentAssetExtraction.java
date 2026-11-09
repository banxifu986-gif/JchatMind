package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentAssetExtraction {
    private String extractionId;

    private String assetId;

    private String documentId;

    private String caption;

    private String ocrText;

    private String summary;

    private String visualKind;

    private String modelName;

    private String modelVersion;

    private String promptVersion;

    private String parserVersion;

    private Integer attemptCount;

    private Double confidence;

    private String rawResult;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}