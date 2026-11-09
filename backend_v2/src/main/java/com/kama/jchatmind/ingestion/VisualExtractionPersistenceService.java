package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentAssetEntityMapper;
import com.kama.jchatmind.mapper.DocumentAssetExtractionMapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.mapper.DocumentAssetRelationMapper;
import com.kama.jchatmind.model.dto.DocumentImageAssetCandidate;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.DocumentAssetEntity;
import com.kama.jchatmind.model.entity.DocumentAssetExtraction;
import com.kama.jchatmind.model.entity.DocumentAssetRelation;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.util.RagChunkSupport;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@AllArgsConstructor
public class VisualExtractionPersistenceService {

    private final DocumentAssetExtractionMapper documentAssetExtractionMapper;
    private final DocumentAssetEntityMapper documentAssetEntityMapper;
    private final DocumentAssetRelationMapper documentAssetRelationMapper;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final DocumentAssetMapper documentAssetMapper;
    private final RagService ragService;
    private final VchordBm25ProjectionService vchordBm25ProjectionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void persist(
            DocumentImageAssetCandidate candidate,
            DocumentAssetExtraction extraction,
            VisualExtractionResult result
    ) {
        LocalDateTime now = LocalDateTime.now();
        documentAssetExtractionMapper.supersedeActiveByAssetId(candidate.getAssetId(), now);
        if (documentAssetExtractionMapper.markActive(
                extraction.getExtractionId(),
                result.caption(),
                result.ocrText(),
                result.summary(),
                result.visualKind(),
                result.confidence(),
                extraction.getRawResult(),
                now
        ) <= 0) {
            throw new BizException("视觉抽取结果写入失败");
        }

        Map<String, String> entityIdByKey = persistEntities(candidate, extraction, result);
        persistRelations(candidate, extraction, result, entityIdByKey);
        persistVisualChunk(candidate, extraction, result);
    }

    private Map<String, String> persistEntities(
            DocumentImageAssetCandidate candidate,
            DocumentAssetExtraction extraction,
            VisualExtractionResult result
    ) {
        Map<String, String> entityIdByKey = new HashMap<>();
        for (VisualExtractionResult.VisualEntity entity : result.entities()) {
            String entityId = UUID.randomUUID().toString();
            entityIdByKey.put(entity.key(), entityId);
            DocumentAssetEntity record = DocumentAssetEntity.builder()
                    .entityId(entityId)
                    .extractionId(extraction.getExtractionId())
                    .documentId(candidate.getDocumentId())
                    .assetId(candidate.getAssetId())
                    .entityKey(entity.key())
                    .name(entity.name())
                    .entityType(entity.type())
                    .bbox(entity.bbox())
                    .confidence(entity.confidence())
                    .build();
            if (documentAssetEntityMapper.insert(record) <= 0) {
                throw new BizException("图片实体写入失败");
            }
        }
        return entityIdByKey;
    }

    private void persistRelations(
            DocumentImageAssetCandidate candidate,
            DocumentAssetExtraction extraction,
            VisualExtractionResult result,
            Map<String, String> entityIdByKey
    ) {
        for (VisualExtractionResult.VisualRelation relation : result.relations()) {
            DocumentAssetRelation record = DocumentAssetRelation.builder()
                    .relationId(UUID.randomUUID().toString())
                    .extractionId(extraction.getExtractionId())
                    .documentId(candidate.getDocumentId())
                    .assetId(candidate.getAssetId())
                    .subjectEntityId(entityIdByKey.get(relation.subject()))
                    .predicate(relation.predicate())
                    .objectEntityId(entityIdByKey.get(relation.object()))
                    .evidenceText(relation.evidence())
                    .confidence(relation.confidence())
                    .build();
            if (documentAssetRelationMapper.insert(record) <= 0) {
                throw new BizException("图片关系写入失败");
            }
        }
    }

    private void persistVisualChunk(
            DocumentImageAssetCandidate candidate,
            DocumentAssetExtraction extraction,
            VisualExtractionResult result
    ) {
        String contentPath = resolveContentPath(candidate);
        String title = buildChunkTitle(candidate, contentPath);
        String content = buildChunkContent(result);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("retrievableTitle", title);
        metadata.put("retrievableTitleSearchText", RetrievableTitleLexicalizer.buildSearchText(
                title, title, contentPath, null, candidate.getFilename()
        ));
        metadata.put("contentPath", contentPath);
        metadata.put("sourceType", candidate.getFiletype());
        metadata.put("sourceName", candidate.getFilename());
        if (candidate.getPageNumber() != null) {
            metadata.put("pageNumber", candidate.getPageNumber());
        }
        metadata.put("visualKind", result.visualKind());
        metadata.put("extractionId", extraction.getExtractionId());
        try {
            metadata.put("asset", Map.of(
                    "id", candidate.getAssetId(),
                    "type", "IMAGE",
                    "locator", objectMapper.readTree(candidate.getLocator())
            ));
        } catch (JsonProcessingException e) {
            throw new BizException("图片资产定位信息序列化失败");
        }

        float[] embedding = ragService.embed(RagChunkSupport.buildChunkEmbeddingText(contentPath, title, content));
        VchordBm25ProjectionService.Projection projection = vchordBm25ProjectionService.project(
                (String) metadata.get("retrievableTitleSearchText"),
                content
        );
        ChunkBgeM3 chunk = ChunkBgeM3.builder()
                .kbId(candidate.getKbId())
                .docId(candidate.getDocumentId())
                .content(content)
                .metadata(writeMetadata(metadata))
                .embedding(embedding)
                .titleBm25Vector(projection.titleVector())
                .contentBm25Vector(projection.contentVector())
                .bm25IndexVersion(projection.indexVersion())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        if (chunkBgeM3Mapper.insert(chunk) <= 0) {
            throw new BizException("视觉分块写入失败");
        }
        if (documentAssetMapper.insertChunkRelation(
                candidate.getAssetId(),
                chunk.getId(),
                candidate.getDocumentId(),
                candidate.getDocumentId()
        ) <= 0) {
            throw new BizException("视觉分块资产关联写入失败");
        }
    }

    private String resolveContentPath(DocumentImageAssetCandidate candidate) {
        try {
            JsonNode locator = objectMapper.readTree(candidate.getLocator());
            if (locator.hasNonNull("sectionPath")) {
                return locator.get("sectionPath").asText();
            }
            if (locator.hasNonNull("pageNumber")) {
                return "第 " + locator.get("pageNumber").asInt() + " 页";
            }
            return candidate.getFilename();
        } catch (JsonProcessingException e) {
            throw new BizException("图片资产定位信息非法");
        }
    }

    private String buildChunkTitle(DocumentImageAssetCandidate candidate, String contentPath) {
        String captionPrefix = "图片";
        return StringUtils.hasText(contentPath) ? captionPrefix + "：" + contentPath : captionPrefix;
    }

    private String buildChunkContent(VisualExtractionResult result) {
        StringBuilder content = new StringBuilder();
        appendLine(content, result.caption());
        appendLine(content, result.ocrText());
        appendLine(content, result.summary());
        for (VisualExtractionResult.VisualEntity entity : result.entities()) {
            content.append(entity.name()).append("(").append(entity.type()).append(")\n");
        }
        for (VisualExtractionResult.VisualRelation relation : result.relations()) {
            content.append(relation.subject()).append(" -> ")
                    .append(relation.predicate()).append(" -> ")
                    .append(relation.object()).append("\n");
        }
        return content.toString().trim();
    }

    private void appendLine(StringBuilder content, String value) {
        if (StringUtils.hasText(value)) {
            content.append(value.trim()).append("\n");
        }
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new BizException("视觉分块元数据序列化失败");
        }
    }
}