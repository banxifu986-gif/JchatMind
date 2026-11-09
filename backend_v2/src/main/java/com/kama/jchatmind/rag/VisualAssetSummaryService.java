package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.DocumentAssetExtractionMapper;
import com.kama.jchatmind.model.dto.VisualAssetSummaryItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class VisualAssetSummaryService {

    static final int SUMMARY_LIMIT = 20;
    private static final int CAPTION_MAX_LENGTH = 80;

    private final DocumentAssetExtractionMapper documentAssetExtractionMapper;
    private final ObjectMapper objectMapper;

    @Value("${rag.visual-extraction.intent-summary-enabled:false}")
    private boolean intentSummaryEnabled;

    public VisualAssetSummaryService(
            DocumentAssetExtractionMapper documentAssetExtractionMapper,
            ObjectMapper objectMapper
    ) {
        this.documentAssetExtractionMapper = documentAssetExtractionMapper;
        this.objectMapper = objectMapper;
    }

    public boolean hasVisualAssets(List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return false;
        }
        return documentAssetExtractionMapper.hasActiveVisualAsset(kbIds);
    }

    public String summarize(List<String> kbIds) {
        if (!intentSummaryEnabled || kbIds == null || kbIds.isEmpty()) {
            return null;
        }
        List<VisualAssetSummaryItem> items =
                documentAssetExtractionMapper.selectActiveVisualAssetSummaries(kbIds, SUMMARY_LIMIT);
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder summary = new StringBuilder();
        for (VisualAssetSummaryItem item : items) {
            if (summary.length() > 0) {
                summary.append("\n");
            }
            summary.append("- ").append(item.getVisualKind());
            String location = resolveLocation(item.getLocator());
            if (StringUtils.hasText(location)) {
                summary.append(" @").append(location);
            }
            if (StringUtils.hasText(item.getEntityTypes())) {
                summary.append(" 实体类型[").append(item.getEntityTypes()).append("]");
            }
            if (StringUtils.hasText(item.getCaption())) {
                summary.append("：").append(truncate(item.getCaption()));
            }
        }
        return summary.toString();
    }

    private String resolveLocation(String locator) {
        if (!StringUtils.hasText(locator)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(locator);
            if (node.hasNonNull("sectionPath")) {
                return node.get("sectionPath").asText();
            }
            if (node.hasNonNull("pageNumber")) {
                return "第 " + node.get("pageNumber").asInt() + " 页";
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String truncate(String caption) {
        return caption.length() <= CAPTION_MAX_LENGTH ? caption : caption.substring(0, CAPTION_MAX_LENGTH) + "…";
    }
}