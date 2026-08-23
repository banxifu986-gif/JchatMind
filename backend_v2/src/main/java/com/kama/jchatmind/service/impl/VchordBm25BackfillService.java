package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.VchordBm25BackfillMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@AllArgsConstructor
public class VchordBm25BackfillService {
    public static final int MAX_BATCH_SIZE = 500;

    private final VchordBm25BackfillMapper backfillMapper;
    private final VchordBm25ProjectionService projectionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public int backfill(int batchSize) {
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("BM25 回填批量必须在 1 到 " + MAX_BATCH_SIZE + " 之间");
        }

        List<ChunkBgeM3> legacyChunks = backfillMapper.selectLegacyBm25ChunksForUpdate(batchSize);
        for (ChunkBgeM3 legacyChunk : legacyChunks) {
            VchordBm25ProjectionService.Projection projection = projectionService.project(
                    titleSearchText(legacyChunk),
                    legacyChunk.getContent()
            );
            if (!isCompleteProjection(projection)) {
                throw new IllegalStateException("BM25 回填生成了不完整投影");
            }
            if (backfillMapper.updateBm25Projection(
                    legacyChunk.getId(),
                    projection.titleVector(),
                    projection.contentVector(),
                    projection.indexVersion()
            ) != 1) {
                throw new IllegalStateException("BM25 回填更新历史分块失败");
            }
        }
        return legacyChunks.size();
    }

    private String titleSearchText(ChunkBgeM3 chunk) {
        if (!StringUtils.hasText(chunk.getId()) || !StringUtils.hasText(chunk.getContent())) {
            throw new IllegalStateException("BM25 回填领取了无效历史分块");
        }
        if (!StringUtils.hasText(chunk.getMetadata())) {
            return "";
        }
        try {
            JsonNode metadata = objectMapper.readTree(chunk.getMetadata());
            if (metadata == null || !metadata.isObject()) {
                throw new IllegalStateException("历史分块元数据不是对象");
            }
            String indexedTitle = metadata.path("retrievableTitleSearchText").asText();
            if (StringUtils.hasText(indexedTitle)) {
                return indexedTitle;
            }
            return RetrievableTitleLexicalizer.buildSearchText(
                    metadata.path("retrievableTitle").asText(),
                    metadata.path("title").asText(),
                    metadata.path("contentPath").asText(),
                    metadata.path("parentContentPath").asText(),
                    metadata.path("sourceName").asText()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("历史分块元数据无法解析", e);
        }
    }

    private boolean isCompleteProjection(VchordBm25ProjectionService.Projection projection) {
        return projection != null
                && StringUtils.hasText(projection.titleVector())
                && StringUtils.hasText(projection.contentVector())
                && projection.indexVersion() != null
                && projection.indexVersion() > 0;
    }
}
