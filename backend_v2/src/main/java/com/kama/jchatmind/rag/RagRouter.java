package com.kama.jchatmind.rag;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
public class RagRouter {

    private static final List<String> DIRECT_PREFIXES = List.of(
            "你好", "您好", "嗨", "hello", "hi", "谢谢", "再见"
    );
    private static final List<String> MULTIMODAL_TERMS = List.of(
            "pdf", "图片", "图像", "截图", "表格", "单元格", "页码", "page"
    );
    private static final List<String> EXTERNAL_TERMS = List.of(
            "最新", "实时", "联网", "网页", "互联网", "官方文档", "release notes"
    );
    private static final List<String> PRIVATE_SCOPE_VIOLATION_TERMS = List.of(
            "其他用户的私有", "其他用户私有", "别人的私有", "他人私有"
    );

    public RagRouteDecision decide(String query, List<String> allowedKbIds) {
        return decide(query, allowedKbIds, true, false, true);
    }

    public RagRouteDecision decide(
            String query,
            List<String> allowedKbIds,
            boolean authorized,
            boolean externalAllowed,
            boolean privateEvidenceAvailable
    ) {
        String normalized = normalize(query);
        List<String> scope = allowedKbIds == null ? List.of() : allowedKbIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();

        if (!StringUtils.hasText(normalized)) {
            return decision(RagRouteDecision.Route.CLARIFY, scope, RagRouteDecision.RewriteMode.NONE,
                    List.of(), 0, false, true, "查询为空，需要补充问题");
        }
        if (isDirect(normalized)) {
            return decision(RagRouteDecision.Route.DIRECT, scope, RagRouteDecision.RewriteMode.NONE,
                    List.of(), 0, false, false, "闲聊无需检索");
        }
        if (!authorized || scope.isEmpty()) {
            return decision(RagRouteDecision.Route.ABSTAIN, List.of(), RagRouteDecision.RewriteMode.NONE,
                    List.of(), 0, false, false, "当前请求没有可用的授权知识范围");
        }
        if (normalized.contains("管理员密码") || normalized.contains("数据库密码")) {
            return decision(RagRouteDecision.Route.ABSTAIN, scope, RagRouteDecision.RewriteMode.NONE,
                    List.of(), 0, false, false, "问题涉及敏感凭据，无法可靠回答");
        }
        if (containsAny(normalized, PRIVATE_SCOPE_VIOLATION_TERMS)) {
            return decision(RagRouteDecision.Route.ABSTAIN, scope, RagRouteDecision.RewriteMode.NONE,
                    List.of(), 0, false, false, "不能提供其他用户的私有知识库来源");
        }
        if (containsAny(normalized, EXTERNAL_TERMS)) {
            if (!externalAllowed) {
                return decision(RagRouteDecision.Route.ABSTAIN, scope, RagRouteDecision.RewriteMode.NONE,
                        List.of(), 0, false, false, "外部资料调用未获用户许可");
            }
            return decision(RagRouteDecision.Route.EXTERNAL_TOOL, scope, RagRouteDecision.RewriteMode.LIGHT,
                    List.of("private", "external"), 5, true, false,
                    "用户允许且私有证据不足时查询受控外部资料");
        }
        if (!privateEvidenceAvailable) {
            return decision(RagRouteDecision.Route.ABSTAIN, scope, RagRouteDecision.RewriteMode.NONE,
                    List.of(), 0, false, false, "当前授权范围内没有足够证据");
        }
        if (containsAny(normalized, MULTIMODAL_TERMS)) {
            return decision(RagRouteDecision.Route.MULTIMODAL_RAG, scope, RagRouteDecision.RewriteMode.CONTEXTUAL,
                    List.of("vector", "metadata", "location"), 5, true, false, "问题需要页码、图片或表格定位");
        }
        if (normalized.contains("对比") || normalized.contains("区别") || normalized.contains("官方")) {
            return decision(RagRouteDecision.Route.HYBRID_RAG, scope, RagRouteDecision.RewriteMode.CONTEXTUAL,
                    List.of("private", "curated_public"), 5, true, false, "需要联合私有知识与精选公开资料");
        }
        return decision(RagRouteDecision.Route.PRIVATE_RAG, scope, RagRouteDecision.RewriteMode.LIGHT,
                List.of("vector", "lexical"), 3, true, false, "默认优先检索授权私有知识库");
    }

    private RagRouteDecision decision(
            RagRouteDecision.Route route,
            List<String> scope,
            RagRouteDecision.RewriteMode rewriteMode,
            List<String> channels,
            int topK,
            boolean rerank,
            boolean clarify,
            String reason
    ) {
        return RagRouteDecision.builder()
                .route(route)
                .searchScope(scope)
                .rewriteMode(rewriteMode)
                .retrievalChannels(channels)
                .topK(topK)
                .rerankEnabled(rerank)
                .needClarification(clarify)
                .reason(reason)
                .build();
    }

    private boolean isDirect(String query) {
        return DIRECT_PREFIXES.stream().anyMatch(query::equals);
    }

    private boolean containsAny(String query, List<String> terms) {
        return terms.stream().anyMatch(query::contains);
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }
}
