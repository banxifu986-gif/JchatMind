package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.DocumentAsset;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.util.RagChunkSupport;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@AllArgsConstructor
public class DefaultIngestionTaskProcessor implements IngestionTaskProcessor {

    private final DocumentMapper documentMapper;
    private final DocumentStorageService documentStorageService;
    private final ObjectMapper objectMapper;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final DocumentAssetMapper documentAssetMapper;
    private final VchordBm25ProjectionService vchordBm25ProjectionService;

    @Override
    @Transactional
    public void process(IngestionTask task) {
        Document document = documentMapper.selectById(task.getDocumentId());
        if (document == null || !Objects.equals(task.getKbId(), document.getKbId())) {
            throw new BizException("无权访问文档");
        }

        Path storedFile = documentStorageService.getFilePath(extractFilePath(document));
        if (storedFile == null) {
            throw new BizException("文档文件不存在");
        }

        List<MarkdownParserService.MarkdownSection> sections = parseSections(document, storedFile);
        boolean pdfDocument = "pdf".equalsIgnoreCase(document.getFiletype());
        boolean markdownDocument = isMarkdownDocument(document);
        LocalDateTime now = LocalDateTime.now();
        List<MarkdownTableAsset> markdownTableAssets = markdownDocument
                ? createMarkdownTableAssets(document, storedFile, now)
                : List.of();
        if (pdfDocument || markdownDocument) {
            documentAssetMapper.deleteByDocumentId(document.getId());
        }
        for (ChunkBgeM3 existingChunk : chunkBgeM3Mapper.selectByDocId(document.getId())) {
            chunkBgeM3Mapper.deleteById(existingChunk.getId());
        }

        for (int index = 0; index < sections.size(); index++) {
            MarkdownParserService.MarkdownSection section = sections.get(index);
            if (!StringUtils.hasText(section.getTitle())) {
                continue;
            }
            DocumentAsset pdfPageAsset = pdfDocument ? createPdfPageAsset(document, section, now) : null;
            String content = section.getContent() == null ? "" : section.getContent();
            List<MarkdownTableAsset> sectionTableAssets = markdownTableAssets.stream()
                    .filter(tableAsset -> content.contains(tableAsset.table().content()))
                    .toList();
            DocumentAsset metadataAsset = pdfPageAsset != null
                    ? pdfPageAsset
                    : sectionTableAssets.isEmpty() ? null : sectionTableAssets.get(0).asset();
            float[] embedding = ragService.embed(RagChunkSupport.buildChunkEmbeddingText(section));
            VchordBm25ProjectionService.Projection projection = vchordBm25ProjectionService.project(
                    RagChunkSupport.buildRetrievableTitleSearchText(section, document.getFilename()),
                    content
            );
            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(document.getKbId())
                    .docId(document.getId())
                    .content(content)
                    .metadata(buildMetadata(section, document, index, metadataAsset))
                    .embedding(embedding)
                    .titleBm25Vector(projection.titleVector())
                    .contentBm25Vector(projection.contentVector())
                    .bm25IndexVersion(projection.indexVersion())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (chunkBgeM3Mapper.insert(chunk) <= 0) {
                throw new BizException("文档分块写入失败");
            }
            if (pdfPageAsset != null) {
                persistDocumentAsset(document, pdfPageAsset, chunk);
            }
            for (MarkdownTableAsset tableAsset : sectionTableAssets) {
                persistDocumentAsset(document, tableAsset.asset(), chunk);
            }
        }
    }

    private DocumentAsset createPdfPageAsset(
            Document document,
            MarkdownParserService.MarkdownSection section,
            LocalDateTime now
    ) {
        Integer pageNumber = section.getPageNumber();
        if (pageNumber == null || pageNumber <= 0) {
            throw new BizException("PDF 页码缺失");
        }
        String content = section.getContent() == null ? "" : section.getContent();
        return DocumentAsset.builder()
                .assetId(UUID.randomUUID().toString())
                .documentId(document.getId())
                .assetType("PDF_PAGE_TEXT")
                .assetKey("page-" + pageNumber)
                .pageNumber(pageNumber)
                .locator(buildPdfPageLocator(pageNumber))
                .contentHash(sha256(content))
                .parserVersion("pdf-text-v1")
                .status("READY")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void persistDocumentAsset(
            Document document,
            DocumentAsset asset,
            ChunkBgeM3 chunk
    ) {
        if (!StringUtils.hasText(chunk.getId())) {
            throw new BizException("文档分块标识生成失败");
        }
        if (documentAssetMapper.insert(asset) <= 0) {
            throw new BizException("文档资产写入失败");
        }
        if (documentAssetMapper.insertChunkRelation(
                asset.getAssetId(),
                chunk.getId(),
                document.getId(),
                document.getId()
        ) <= 0) {
            throw new BizException("文档资产关联写入失败");
        }
    }

    private String buildPdfPageLocator(int pageNumber) {
        try {
            return objectMapper.writeValueAsString(Map.of("pageNumber", pageNumber));
        } catch (JsonProcessingException e) {
            throw new BizException("文档资产定位信息序列化失败");
        }
    }

    private List<MarkdownTableAsset> createMarkdownTableAssets(
            Document document,
            Path storedFile,
            LocalDateTime now
    ) {
        List<MarkdownParserService.MarkdownTable> tables;
        try (InputStream inputStream = Files.newInputStream(storedFile)) {
            tables = markdownParserService.parseMarkdownTables(inputStream);
        } catch (IOException e) {
            throw new BizException("读取文档文件失败");
        }
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        List<MarkdownTableAsset> assets = new ArrayList<>();
        for (int index = 0; index < tables.size(); index++) {
            MarkdownParserService.MarkdownTable table = tables.get(index);
            if (table == null || !StringUtils.hasText(table.content())
                    || table.startLine() <= 0 || table.endLine() < table.startLine()) {
                continue;
            }
            assets.add(new MarkdownTableAsset(
                    table,
                    DocumentAsset.builder()
                            .assetId(UUID.randomUUID().toString())
                            .documentId(document.getId())
                            .assetType("TABLE")
                            .assetKey("table-" + (index + 1))
                            .locator(buildTableLocator(table))
                            .contentHash(sha256(table.content()))
                            .parserVersion("markdown-table-v1")
                            .status("READY")
                            .createdAt(now)
                            .updatedAt(now)
                            .build()
            ));
        }
        return List.copyOf(assets);
    }

    private String buildTableLocator(MarkdownParserService.MarkdownTable table) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "startLine", table.startLine(),
                    "endLine", table.endLine()
            ));
        } catch (JsonProcessingException e) {
            throw new BizException("文档资产定位信息序列化失败");
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private List<MarkdownParserService.MarkdownSection> parseSections(Document document, Path storedFile) {
        String filetype = document.getFiletype();
        if ("md".equalsIgnoreCase(filetype)
                || "markdown".equalsIgnoreCase(filetype)
                || "txt".equalsIgnoreCase(filetype)
                || "html".equalsIgnoreCase(filetype)) {
            try (InputStream inputStream = Files.newInputStream(storedFile)) {
                List<MarkdownParserService.MarkdownSection> sections = "html".equalsIgnoreCase(filetype)
                        ? markdownParserService.parseHtml(inputStream)
                        : markdownParserService.parseMarkdown(inputStream);
                if (!sections.isEmpty()) {
                    return sections;
                }
            } catch (IOException e) {
                throw new BizException("读取文档文件失败");
            }
            try {
                return List.of(plainDocumentSection(document, Files.readString(storedFile, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new BizException("读取文档文件失败");
            }
        }
        if ("pdf".equalsIgnoreCase(filetype)) {
            try (InputStream inputStream = Files.newInputStream(storedFile)) {
                return markdownParserService.parsePdf(inputStream);
            } catch (IOException e) {
                throw new BizException("读取文档文件失败");
            } catch (IllegalArgumentException e) {
                throw new BizException("PDF 解析失败");
            }
        }
        throw new BizException("不支持的文档类型");
    }

    private boolean isMarkdownDocument(Document document) {
        return "md".equalsIgnoreCase(document.getFiletype())
                || "markdown".equalsIgnoreCase(document.getFiletype());
    }

    private MarkdownParserService.MarkdownSection plainDocumentSection(Document document, String content) {
        String title = document.getFilename();
        int extensionIndex = title == null ? -1 : title.lastIndexOf('.');
        if (extensionIndex > 0) {
            title = title.substring(0, extensionIndex);
        }
        if (!StringUtils.hasText(title)) {
            title = "文档";
        }
        return new MarkdownParserService.MarkdownSection(
                title,
                content,
                title,
                null,
                1,
                false,
                MarkdownParserService.SectionType.LEAF_CONTENT,
                1,
                content == null ? 0 : content.length()
        );
    }

    private String extractFilePath(Document document) {
        try {
            JsonNode metadata = objectMapper.readTree(document.getMetadata());
            String filePath = metadata.path("filePath").asText();
            if (!StringUtils.hasText(filePath)) {
                throw new BizException("文档文件不存在");
            }
            return filePath;
        } catch (JsonProcessingException e) {
            throw new BizException("文档元数据非法");
        }
    }

    private String buildMetadata(
            MarkdownParserService.MarkdownSection section,
            Document document,
            int index,
            DocumentAsset asset
    ) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>(RagChunkSupport.buildChunkMetadata(
                    section,
                    document.getFiletype(),
                    document.getFilename(),
                    index
            ));
            if (asset != null) {
                metadata.put("asset", Map.of(
                        "id", asset.getAssetId(),
                        "type", asset.getAssetType(),
                        "locator", objectMapper.readTree(asset.getLocator())
                ));
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new BizException("分块元数据序列化失败");
        }
    }

    private record MarkdownTableAsset(
            MarkdownParserService.MarkdownTable table,
            DocumentAsset asset
    ) {
    }
}
