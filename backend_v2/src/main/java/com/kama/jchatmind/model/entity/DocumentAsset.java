package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentAsset {
    private String assetId;

    private String documentId;

    private String assetType;

    private String assetKey;

    private Integer pageNumber;

    private String locator;

    private String contentHash;

    private String parserVersion;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
