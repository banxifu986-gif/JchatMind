package com.kama.jchatmind.model.dto;

import lombok.Data;

@Data
public class DocumentImageAssetCandidate {
    private String assetId;

    private String documentId;

    private String kbId;

    private String contentHash;

    private Integer pageNumber;

    private String locator;

    private String filename;

    private String filetype;
}