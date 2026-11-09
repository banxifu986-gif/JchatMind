package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentAssetRelation {
    private String relationId;

    private String extractionId;

    private String documentId;

    private String assetId;

    private String subjectEntityId;

    private String predicate;

    private String objectEntityId;

    private String evidenceText;

    private double[] evidenceBbox;

    private String attributes;

    private Double confidence;
}