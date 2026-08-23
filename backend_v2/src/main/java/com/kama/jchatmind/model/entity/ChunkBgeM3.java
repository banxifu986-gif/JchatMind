package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;
import java.util.Arrays;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @TableName chunk_bge_m3
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChunkBgeM3 {
    private String id;

    private String kbId;

    private String docId;

    private String content;

    private String metadata;

    private float[] embedding;

    private String titleBm25Vector;

    private String contentBm25Vector;

    private Integer bm25IndexVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        ChunkBgeM3 other = (ChunkBgeM3) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getKbId() == null ? other.getKbId() == null : this.getKbId().equals(other.getKbId()))
            && (this.getDocId() == null ? other.getDocId() == null : this.getDocId().equals(other.getDocId()))
            && (this.getContent() == null ? other.getContent() == null : this.getContent().equals(other.getContent()))
            && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
            && (this.getEmbedding() == null ? other.getEmbedding() == null : Arrays.equals(this.getEmbedding(), other.getEmbedding()))
            && (this.getTitleBm25Vector() == null ? other.getTitleBm25Vector() == null : this.getTitleBm25Vector().equals(other.getTitleBm25Vector()))
            && (this.getContentBm25Vector() == null ? other.getContentBm25Vector() == null : this.getContentBm25Vector().equals(other.getContentBm25Vector()))
            && (this.getBm25IndexVersion() == null ? other.getBm25IndexVersion() == null : this.getBm25IndexVersion().equals(other.getBm25IndexVersion()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
            && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getKbId() == null) ? 0 : getKbId().hashCode());
        result = prime * result + ((getDocId() == null) ? 0 : getDocId().hashCode());
        result = prime * result + ((getContent() == null) ? 0 : getContent().hashCode());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getEmbedding() == null) ? 0 : Arrays.hashCode(getEmbedding()));
        result = prime * result + ((getTitleBm25Vector() == null) ? 0 : getTitleBm25Vector().hashCode());
        result = prime * result + ((getContentBm25Vector() == null) ? 0 : getContentBm25Vector().hashCode());
        result = prime * result + ((getBm25IndexVersion() == null) ? 0 : getBm25IndexVersion().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [" +
                "Hash = " + hashCode() +
                ", id=" + id +
                ", kbId=" + kbId +
                ", docId=" + docId +
                ", content=" + content +
                ", metadata=" + metadata +
                ", embedding=" + Arrays.toString(embedding) +
                ", titleBm25Vector=" + titleBm25Vector +
                ", contentBm25Vector=" + contentBm25Vector +
                ", bm25IndexVersion=" + bm25IndexVersion +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
