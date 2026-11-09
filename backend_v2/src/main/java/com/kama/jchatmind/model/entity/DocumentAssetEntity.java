package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentAssetEntity {
    private String entityId;

    private String extractionId;

    private String documentId;

    private String assetId;

    private String entityKey;

    private String name;

    private String entityType;

    private String aliases;

    private String attributes;

    private double[] bbox;

    private Double confidence;
}