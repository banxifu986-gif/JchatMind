package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalResult {
    private String chunkId;

    private String kbId;

    private String docId;

    private String content;

    private String metadata;

    private Double distance;

    private Integer rank;
}
