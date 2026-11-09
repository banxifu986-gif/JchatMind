package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagRouterVisualHintTest {

    private final RagRouter router = new RagRouter();

    private static final List<String> SCOPE = List.of("kb-1");

    @Test
    void shouldRouteMultimodalWhenIntentRequiresVisualEvidence() {
        RagRouteDecision decision = router.decide(
                "解释这张架构图",
                SCOPE,
                true,
                false,
                true,
                new RagRouter.VisualEvidenceHint(true, true)
        );

        assertThat(decision.route()).isEqualTo(RagRouteDecision.Route.MULTIMODAL_RAG);
        assertThat(decision.needClarification()).isFalse();
        assertThat(decision.reason()).contains("图片证据");
    }

    @Test
    void shouldClarifyWhenVisualEvidenceRequiredButNoAssetsAvailable() {
        RagRouteDecision decision = router.decide(
                "图里谁调用谁",
                SCOPE,
                true,
                false,
                true,
                new RagRouter.VisualEvidenceHint(true, false)
        );

        assertThat(decision.route()).isEqualTo(RagRouteDecision.Route.CLARIFY);
        assertThat(decision.needClarification()).isTrue();
        assertThat(decision.reason()).contains("没有可用的图片资产");
    }

    @Test
    void shouldKeepExternalAndDirectRoutesWhenHintPresent() {
        RagRouteDecision external = router.decide(
                "查询最新架构图资料",
                SCOPE,
                true,
                true,
                true,
                new RagRouter.VisualEvidenceHint(true, true)
        );
        assertThat(external.route()).isEqualTo(RagRouteDecision.Route.EXTERNAL_TOOL);

        RagRouteDecision direct = router.decide(
                "你好",
                SCOPE,
                true,
                false,
                true,
                new RagRouter.VisualEvidenceHint(true, true)
        );
        assertThat(direct.route()).isEqualTo(RagRouteDecision.Route.DIRECT);
    }

    @Test
    void shouldFallBackToKeywordsWithoutHint() {
        RagRouteDecision decision = router.decide("解释这张架构图", SCOPE, true, false, true);

        assertThat(decision.route()).isEqualTo(RagRouteDecision.Route.PRIVATE_RAG);
    }
}