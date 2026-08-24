package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.rag.RagRouteDecision;
import com.kama.jchatmind.rag.RagRouter;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class McpKnowledgeTool {

    private static final String PRIVATE_KNOWLEDGE_ACCESS_DENIED = "当前 MCP 调用未绑定用户身份，禁止访问私有知识库。";

    private final RagService ragService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final McpPrincipalAccessService mcpPrincipalAccessService;
    private final RagRouter ragRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpKnowledgeTool(
            RagService ragService,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            McpPrincipalAccessService mcpPrincipalAccessService
    ) {
        this(ragService, knowledgeBaseMapper, knowledgeBaseAccessService, mcpPrincipalAccessService, new RagRouter());
    }

    @Autowired
    public McpKnowledgeTool(
            RagService ragService,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            McpPrincipalAccessService mcpPrincipalAccessService,
            RagRouter ragRouter
    ) {
        this.ragService = ragService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.mcpPrincipalAccessService = mcpPrincipalAccessService;
        this.ragRouter = ragRouter;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "mcpKnowledgeQuery",
            description = "从指定知识库执行语义检索（RAG）。参数：query（查询文本，必传）、kbIds（知识库 ID 数组，必传）。返回结构化检索结果，包含知识库名、来源、路径和内容。"
    )
    public String search(String query, List<String> kbIds) {
        McpCallerIdentity caller = resolveCaller();
        if (caller == null) {
            return PRIVATE_KNOWLEDGE_ACCESS_DENIED;
        }
        try {
            List<String> accessibleKbIds = knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(
                    kbIds,
                    String.valueOf(caller.userId())
            );
            if (accessibleKbIds.isEmpty()) {
                mcpPrincipalAccessService.recordKnowledgeQuery(
                        caller,
                        resolveCorrelationId(),
                        "ALLOW",
                        accessibleKbIds,
                        "empty_scope"
                );
                return "";
            }
            RagRouteDecision route = ragRouter.decide(query, accessibleKbIds, true, false, true);
            if (route.route() == RagRouteDecision.Route.ABSTAIN
                    || route.route() == RagRouteDecision.Route.CLARIFY
                    || route.route() == RagRouteDecision.Route.DIRECT) {
                mcpPrincipalAccessService.recordKnowledgeQuery(
                        caller,
                        resolveCorrelationId(),
                        route.route() == RagRouteDecision.Route.ABSTAIN ? "DENY" : "ALLOW",
                        accessibleKbIds,
                        "route_" + route.route().name().toLowerCase()
                );
                return route.reason();
            }
            List<RagRetrievalResult> results = ragService.retrieve(accessibleKbIds, query, route.topK());
            if (results == null || results.isEmpty()) {
                mcpPrincipalAccessService.recordKnowledgeQuery(
                        caller,
                        resolveCorrelationId(),
                        "ABSTAIN",
                        accessibleKbIds,
                        "no_evidence"
                );
                return "当前授权知识范围内没有足够证据，无法可靠回答。";
            }
            mcpPrincipalAccessService.recordKnowledgeQuery(
                    caller,
                    resolveCorrelationId(),
                    "ALLOW",
                    accessibleKbIds,
                    "retrieved"
            );
            return formatResults(results, buildKbNameMap(accessibleKbIds));
        } catch (BizException e) {
            mcpPrincipalAccessService.recordKnowledgeQuery(
                    caller,
                    resolveCorrelationId(),
                    "DENY",
                    kbIds,
                    "knowledge_base_access_denied"
            );
            return PRIVATE_KNOWLEDGE_ACCESS_DENIED;
        }
    }

    private McpCallerIdentity resolveCaller() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object caller = attributes.getAttribute(
                McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST
        );
        return caller instanceof McpCallerIdentity identity ? identity : null;
    }

    private String resolveCorrelationId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object correlationId = attributes.getAttribute(
                McpServerConfig.McpApiKeyFilter.CORRELATION_ID_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST
        );
        return correlationId instanceof String value ? value : null;
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
        String pageNumber = extractMetadataText(result.getMetadata(), "pageNumber");
        String content = StringUtils.hasText(result.getContent()) ? result.getContent().trim() : "";
        return "知识库: " + kbName + "\n"
                + "来源: " + defaultText(sourceName) + "\n"
                + "路径: " + defaultText(contentPath) + "\n"
                + "引用: " + defaultText(result.getChunkId())
                + (StringUtils.hasText(pageNumber) ? " | 页码: " + pageNumber : "") + "\n"
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
            return node != null && node.isValueNode() ? node.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }
}
