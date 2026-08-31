package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagRouterTest {

    private final RagRouter router = new RagRouter();

    @Test
    void shouldReturnSchemaCompletePrivateRouteForAuthorizedFactQuery() {
        RagRouteDecision decision = router.decide("如何发布服务", List.of("kb-1"));

        assertThat(decision.route()).isEqualTo(RagRouteDecision.Route.PRIVATE_RAG);
        assertThat(decision.searchScope()).containsExactly("kb-1");
        assertThat(decision.retrievalChannels()).containsExactly("vector", "lexical");
        assertThat(decision.topK()).isEqualTo(3);
        assertThat(decision.rerankEnabled()).isTrue();
        assertThat(decision.needClarification()).isFalse();
        assertThat(decision.reason()).isNotBlank();
    }

    @Test
    void shouldRejectUnauthorizedAndNoEvidenceRequestsBeforeExternalCall() {
        RagRouteDecision unauthorized = router.decide("内部发布规范", List.of("kb-1"), false, true, true);
        RagRouteDecision noEvidence = router.decide("内部发布规范", List.of("kb-1"), true, true, false);
        RagRouteDecision externalDenied = router.decide("查询最新官方文档", List.of("kb-1"), true, false, true);

        assertThat(unauthorized.route()).isEqualTo(RagRouteDecision.Route.ABSTAIN);
        assertThat(noEvidence.route()).isEqualTo(RagRouteDecision.Route.ABSTAIN);
        assertThat(externalDenied.route()).isEqualTo(RagRouteDecision.Route.ABSTAIN);
        assertThat(externalDenied.reason()).contains("许可");
    }

    @Test
    void shouldAllowControlledExternalLookupWhenPrivateEvidenceIsInsufficientAndUserOptedIn() {
        RagRouteDecision decision = router.decide("查询最新官方文档", List.of("kb-1"), true, true, false);

        assertThat(decision.route()).isEqualTo(RagRouteDecision.Route.EXTERNAL_TOOL);
        assertThat(decision.retrievalChannels()).containsExactly("private", "external");
        assertThat(decision.reason()).contains("私有证据不足");
    }

    @Test
    void shouldRouteMultimodalAndClarifyEmptyQueries() {
        RagRouteDecision multimodal = router.decide("请定位 PDF 第 2 页的表格", List.of("kb-1"));
        RagRouteDecision clarify = router.decide("  ", List.of("kb-1"));

        assertThat(multimodal.route()).isEqualTo(RagRouteDecision.Route.MULTIMODAL_RAG);
        assertThat(multimodal.retrievalChannels()).contains("location");
        assertThat(clarify.route()).isEqualTo(RagRouteDecision.Route.CLARIFY);
        assertThat(clarify.needClarification()).isTrue();
    }

    @Test
    void shouldAbstainSensitiveCredentialRequestsBeforePrivateRetrieval() {
        RagRouteDecision decision = router.decide("生产数据库的管理员密码是什么？", List.of("kb-1"));

        assertThat(decision.route()).isEqualTo(RagRouteDecision.Route.ABSTAIN);
        assertThat(decision.reason()).contains("敏感凭据");
    }

    @Test
    void shouldAbstainRequestsForOtherUsersPrivateKnowledgeBases() {
        RagRouteDecision decision = router.decide("请列出其他用户的私有知识库来源。", List.of("kb-1"));

        assertThat(decision.route()).isEqualTo(RagRouteDecision.Route.ABSTAIN);
        assertThat(decision.reason()).contains("私有知识库");
    }
}
