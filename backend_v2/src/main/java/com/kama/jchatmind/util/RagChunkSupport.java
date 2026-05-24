package com.kama.jchatmind.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RagChunkSupport {

    private RagChunkSupport() {
    }

    public static Map<String, Object> buildChunkMetadata(
            MarkdownParserService.MarkdownSection section,
            String sourceType,
            String sourceName,
            int sectionIndex
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", section.getTitle());
        metadata.put("retrievableTitle", section.getTitle());
        metadata.put(
                "retrievableTitleSearchText",
                RetrievableTitleLexicalizer.buildSearchText(
                        section.getTitle(),
                        section.getTitle(),
                        section.getContentPath(),
                        section.getParentContentPath(),
                        sourceName
                )
        );
        metadata.put("contentPath", section.getContentPath());
        metadata.put("parentContentPath", section.getParentContentPath());
        metadata.put("sourceType", sourceType);
        metadata.put("sourceName", sourceName);
        metadata.put("sectionIndex", sectionIndex);
        metadata.put("headingLevel", section.getHeadingLevel());
        metadata.put("hasChildren", section.isHasChildren());
        metadata.put("sectionType", section.getSectionType() == null ? null : section.getSectionType().name());
        metadata.put("pathDepth", section.getPathDepth());
        metadata.put("localContentLength", section.getLocalContentLength());
        return metadata;
    }

    public static String buildChunkMetadataJson(
            ObjectMapper objectMapper,
            MarkdownParserService.MarkdownSection section,
            String sourceType,
            String sourceName,
            int sectionIndex
    ) throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                buildChunkMetadata(section, sourceType, sourceName, sectionIndex)
        );
    }

    public static String buildChunkEmbeddingText(MarkdownParserService.MarkdownSection section) {
        return buildChunkEmbeddingText(section.getContentPath(), section.getTitle(), section.getContent());
    }

    public static String buildChunkEmbeddingText(String contentPath, String title, String content) {
        String effectiveTitle = StringUtils.hasText(contentPath) ? contentPath.trim() : title;
        if (!StringUtils.hasText(content)) {
            return effectiveTitle;
        }
        return effectiveTitle + "\n" + title + "\n" + content.trim();
    }
}
