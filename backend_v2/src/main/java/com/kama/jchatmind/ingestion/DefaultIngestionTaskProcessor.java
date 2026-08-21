package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Component
@AllArgsConstructor
public class DefaultIngestionTaskProcessor implements IngestionTaskProcessor {

    private final DocumentMapper documentMapper;
    private final DocumentStorageService documentStorageService;
    private final ObjectMapper objectMapper;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

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
        for (ChunkBgeM3 existingChunk : chunkBgeM3Mapper.selectByDocId(document.getId())) {
            chunkBgeM3Mapper.deleteById(existingChunk.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < sections.size(); index++) {
            MarkdownParserService.MarkdownSection section = sections.get(index);
            if (!StringUtils.hasText(section.getTitle())) {
                continue;
            }
            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(document.getKbId())
                    .docId(document.getId())
                    .content(section.getContent() == null ? "" : section.getContent())
                    .metadata(buildMetadata(section, document, index))
                    .embedding(ragService.embed(RagChunkSupport.buildChunkEmbeddingText(section)))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (chunkBgeM3Mapper.insert(chunk) <= 0) {
                throw new BizException("文档分块写入失败");
            }
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
            int index
    ) {
        try {
            return RagChunkSupport.buildChunkMetadataJson(
                    objectMapper,
                    section,
                    document.getFiletype(),
                    document.getFilename(),
                    index
            );
        } catch (JsonProcessingException e) {
            throw new BizException("分块元数据序列化失败");
        }
    }
}
