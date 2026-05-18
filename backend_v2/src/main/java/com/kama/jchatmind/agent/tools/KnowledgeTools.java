package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final ChatSessionFacadeService chatSessionFacadeService;
    private final String chatSessionId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeTools(RagService ragService, ChatSessionFacadeService chatSessionFacadeService) {
        this(ragService, chatSessionFacadeService, null);
    }

    private KnowledgeTools(RagService ragService,
                           ChatSessionFacadeService chatSessionFacadeService,
                           String chatSessionId) {
        this.ragService = ragService;
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.chatSessionId = chatSessionId;
    }

    public KnowledgeTools fork(String chatSessionId) {
        return new KnowledgeTools(ragService, chatSessionFacadeService, chatSessionId);
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回与查询最相关的知识片段。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        RagRetrievalContext retrievalContext = loadRetrievalContext();
        List<RagRetrievalResult> results = ragService.retrieve(kbsId, query, retrievalContext, 3);
        updateRetrievalContext(results);
        return results.stream()
                .map(RagRetrievalResult::getContent)
                .toList()
                .stream()
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private RagRetrievalContext loadRetrievalContext() {
        if (!StringUtils.hasText(chatSessionId)) {
            return null;
        }
        return chatSessionFacadeService.getRetrievalContext(chatSessionId);
    }

    private void updateRetrievalContext(List<RagRetrievalResult> results) {
        if (!StringUtils.hasText(chatSessionId) || results == null || results.isEmpty()) {
            return;
        }
        RagRetrievalContext context = buildContextFromTopResult(results.get(0));
        if (context != null && context.hasContext()) {
            chatSessionFacadeService.updateRetrievalContext(chatSessionId, context);
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
}
