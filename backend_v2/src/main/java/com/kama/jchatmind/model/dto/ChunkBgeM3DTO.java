package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChunkBgeM3DTO {
    private String id;

    private String kbId;

    private String docId;

    private String content;

    private MetaData metadata;

    private float[] embedding;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    public static class MetaData {
        private String title;

        private String retrievableTitle;

        private String retrievableTitleSearchText;

        private String contentPath;

        private String sourceType;

        private String sourceName;

        private Integer sectionIndex;
    }
}
