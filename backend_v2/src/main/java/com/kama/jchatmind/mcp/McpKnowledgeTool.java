package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.RagService;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class McpKnowledgeTool {

    private final RagService ragService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpKnowledgeTool(RagService ragService, KnowledgeBaseMapper knowledgeBaseMapper) {
        this.ragService = ragService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "mcpKnowledgeQuery",
            description = "从指定知识库执行语义检索（RAG）。参数：query（查询文本，必传）、kbIds（知识库 ID 数组，必传）。返回结构化检索结果，包含知识库名、来源、路径和内容。"
    )
    public String search(String query, List<String> kbIds) {
        if (!StringUtils.hasText(query)) {
            return "查询文本不能为空。";
        }
        if (CollectionUtils.isEmpty(kbIds)) {
            return "知识库 ID 列表不能为空，请指定要检索的知识库。";
        }

        Map<String, String> kbIdToName = buildKbNameMap(kbIds);
        List<RagRetrievalResult> results = ragService.retrieve(kbIds, query, null, 3);

        return formatResults(results, kbIdToName);
    }

    private Map<String, String> buildKbNameMap(List<String> kbIds) {
        Map<String, String> map = new LinkedHashMap<>();
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(kbIds);
        if (!CollectionUtils.isEmpty(knowledgeBases)) {
            for (KnowledgeBase kb : knowledgeBases) {
                if (kb != null && StringUtils.hasText(kb.getId())) {
                    map.put(kb.getId(), StringUtils.hasText(kb.getName()) ? kb.getName() : kb.getId());
                }
            }
        }
        return map;
    }

    private String formatResults(List<RagRetrievalResult> results, Map<String, String> kbIdToName) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        return results.stream()
                .map(result -> formatResultBlock(result, kbIdToName))
                .collect(Collectors.joining("\n\n"));
    }

    private String formatResultBlock(RagRetrievalResult result, Map<String, String> kbIdToName) {
        String kbName = resolveKbName(result.getKbId(), kbIdToName);
        String sourceName = extractMetadataText(result.getMetadata(), "sourceName");
        String contentPath = extractMetadataText(result.getMetadata(), "contentPath");
        String content = StringUtils.hasText(result.getContent()) ? result.getContent().trim() : "";
        return "知识库: " + kbName + "\n"
                + "来源: " + defaultText(sourceName) + "\n"
                + "路径: " + defaultText(contentPath) + "\n"
                + "内容: " + content;
    }

    private String resolveKbName(String kbId, Map<String, String> kbIdToName) {
        if (!StringUtils.hasText(kbId)) {
            return "未知知识库";
        }
        String name = kbIdToName.get(kbId);
        return StringUtils.hasText(name) ? name : kbId;
    }

    private String extractMetadataText(String metadata, String fieldName) {
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode node = root.get(fieldName);
            return node != null && node.isTextual() ? node.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }
}
