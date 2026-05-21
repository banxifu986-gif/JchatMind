package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.RagService;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final ChatSessionFacadeService chatSessionFacadeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userId;
    private String chatSessionId;
    private Map<String, KnowledgeBaseDTO> allowedKbMap;

    public KnowledgeTools(RagService ragService, ChatSessionFacadeService chatSessionFacadeService) {
        this.ragService = ragService;
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.userId = null;
        this.chatSessionId = null;
        this.allowedKbMap = Map.of();
    }

    public KnowledgeTools fork(String userId, String chatSessionId, List<KnowledgeBaseDTO> allowedKbs) {
        KnowledgeTools tool = new KnowledgeTools(ragService, chatSessionFacadeService);
        tool.userId = userId;
        tool.chatSessionId = chatSessionId;
        tool.allowedKbMap = buildAllowedKbMap(allowedKbs);
        return tool;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从可访问知识库执行语义检索（RAG）。可传知识库 ID 数组（kbIds）和查询文本（query）；不传 kbIds 时默认搜索当前 Agent 全部可访问知识库；返回结果会带知识库、来源和路径信息。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从可访问知识库中执行语义检索（RAG）。参数为查询文本（query）和可选知识库 ID 数组（kbIds）；不传 kbIds 时默认搜索当前 Agent 全部可访问知识库；返回结果带知识库、来源和路径信息。"
    )
    public String knowledgeQuery(String query, List<String> kbIds) {
        RagRetrievalContext retrievalContext = loadRetrievalContext();
        List<String> effectiveKbIds = resolveEffectiveKbIds(kbIds, retrievalContext);
        if (effectiveKbIds.isEmpty()) {
            return "未找到可检索的知识库，请检查当前 Agent 是否已配置可访问知识库，或传入的 kbIds 是否都在授权范围内。";
        }
        retrievalContext = alignRetrievalContext(retrievalContext, kbIds, effectiveKbIds);
        List<RagRetrievalResult> results = ragService.retrieve(effectiveKbIds, query, retrievalContext, 3);
        updateRetrievalContext(results);
        return formatResults(results);
    }

    private RagRetrievalContext loadRetrievalContext() {
        if (!StringUtils.hasText(chatSessionId) || !StringUtils.hasText(userId)) {
            return null;
        }
        return chatSessionFacadeService.getRetrievalContext(userId, chatSessionId);
    }

    private void updateRetrievalContext(List<RagRetrievalResult> results) {
        if (!StringUtils.hasText(chatSessionId) || !StringUtils.hasText(userId) || results == null || results.isEmpty()) {
            return;
        }
        RagRetrievalContext context = buildContextFromTopResult(results.get(0));
        if (context != null && context.hasContext()) {
            chatSessionFacadeService.updateRetrievalContext(userId, chatSessionId, context);
        }
    }

    private RagRetrievalContext buildContextFromTopResult(RagRetrievalResult result) {
        if (result == null || !StringUtils.hasText(result.getMetadata())) {
            return null;
        }
        String sourceType = extractMetadataText(result.getMetadata(), "sourceType");
        String sourceName = extractMetadataText(result.getMetadata(), "sourceName");
        String contentPath = parentContentPath(extractMetadataText(result.getMetadata(), "contentPath"));
        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId(StringUtils.hasText(result.getKbId()) ? result.getKbId() : null)
                .sourceType(StringUtils.hasText(sourceType) ? sourceType : null)
                .sourceName(StringUtils.hasText(sourceName) ? sourceName : null)
                .contentPath(StringUtils.hasText(contentPath) ? contentPath : null)
                .build();
        return context.hasContext() ? context : null;
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

    private String parentContentPath(String contentPath) {
        if (!StringUtils.hasText(contentPath)) {
            return null;
        }
        int separatorIndex = contentPath.lastIndexOf(" > ");
        if (separatorIndex <= 0) {
            return contentPath;
        }
        return contentPath.substring(0, separatorIndex);
    }

    private Map<String, KnowledgeBaseDTO> buildAllowedKbMap(List<KnowledgeBaseDTO> allowedKbs) {
        if (CollectionUtils.isEmpty(allowedKbs)) {
            return Map.of();
        }
        Map<String, KnowledgeBaseDTO> map = new LinkedHashMap<>();
        for (KnowledgeBaseDTO allowedKb : allowedKbs) {
            if (allowedKb == null || !StringUtils.hasText(allowedKb.getId())) {
                continue;
            }
            map.put(allowedKb.getId(), allowedKb);
        }
        return map;
    }

    private List<String> resolveEffectiveKbIds(List<String> kbIds, RagRetrievalContext retrievalContext) {
        if (allowedKbMap.isEmpty()) {
            return List.of();
        }
        if (CollectionUtils.isEmpty(kbIds)) {
            if (retrievalContext != null
                    && StringUtils.hasText(retrievalContext.getKbId())
                    && allowedKbMap.containsKey(retrievalContext.getKbId())) {
                return List.of(retrievalContext.getKbId());
            }
            return new ArrayList<>(allowedKbMap.keySet());
        }
        return kbIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(allowedKbMap::containsKey)
                .distinct()
                .toList();
    }

    private RagRetrievalContext alignRetrievalContext(
            RagRetrievalContext retrievalContext,
            List<String> requestedKbIds,
            List<String> effectiveKbIds
    ) {
        if (retrievalContext == null || !retrievalContext.hasContext()) {
            return null;
        }
        if (CollectionUtils.isEmpty(requestedKbIds)) {
            if (!StringUtils.hasText(retrievalContext.getKbId())) {
                return retrievalContext;
            }
            if (effectiveKbIds.contains(retrievalContext.getKbId())) {
                return retrievalContext;
            }
            return null;
        }
        if (!StringUtils.hasText(retrievalContext.getKbId())) {
            return null;
        }
        if (StringUtils.hasText(retrievalContext.getKbId()) && !effectiveKbIds.contains(retrievalContext.getKbId())) {
            return null;
        }
        return retrievalContext;
    }

    private String formatResults(List<RagRetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        return results.stream()
                .map(this::formatResultBlock)
                .collect(Collectors.joining("\n\n"));
    }

    private String formatResultBlock(RagRetrievalResult result) {
        String kbName = resolveKnowledgeBaseName(result.getKbId());
        String sourceName = extractMetadataText(result.getMetadata(), "sourceName");
        String contentPath = extractMetadataText(result.getMetadata(), "contentPath");
        String content = StringUtils.hasText(result.getContent()) ? result.getContent().trim() : "";
        return "知识库: " + kbName + "\n"
                + "来源: " + defaultText(sourceName) + "\n"
                + "路径: " + defaultText(contentPath) + "\n"
                + "内容: " + content;
    }

    private String resolveKnowledgeBaseName(String kbId) {
        if (!StringUtils.hasText(kbId)) {
            return "未知知识库";
        }
        KnowledgeBaseDTO knowledgeBaseDTO = allowedKbMap.get(kbId);
        if (knowledgeBaseDTO == null || !StringUtils.hasText(knowledgeBaseDTO.getName())) {
            return kbId;
        }
        return knowledgeBaseDTO.getName();
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }
}
