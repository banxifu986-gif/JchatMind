package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeToolsScopeTest {

    @Test
    void shouldNotRetrieveWhenAgentHasEmptyKnowledgeBaseBinding() {
        RagService ragService = mock(RagService.class);
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of());

        String result = tool.knowledgeQuery("问题", null);

        assertThat(result).isEqualTo("未找到可检索的知识库，请检查当前 Agent 是否已配置可访问知识库，或传入的 kbIds 是否都在授权范围内。");
        verifyNoInteractions(ragService);
    }

    @Test
    void shouldNarrowMultipleKnowledgeBaseRequestToAgentBinding() {
        RagService ragService = mock(RagService.class);
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(
                        KnowledgeBaseDTO.builder().id("kb-1").build(),
                        KnowledgeBaseDTO.builder().id("kb-2").build()
                ));
        when(ragService.retrieve(List.of("kb-2"), "问题", null, 3)).thenReturn(List.of());

        String result = tool.knowledgeQuery("问题", List.of("foreign-kb", "kb-2"));

        assertThat(result).contains("没有足够证据");
        verify(ragService).retrieve(List.of("kb-2"), "问题", null, 3);
    }

    @Test
    void shouldSearchAllAgentKnowledgeBasesWhenKbIdsAreOmitted() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalContext previousContext = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("旧文档")
                .build();
        when(sessionService.getRetrievalContext("session-1", "7")).thenReturn(previousContext);
        when(ragService.retrieve(
                eq(List.of("kb-1", "kb-2")),
                eq("新主题"),
                argThat(context -> context != null && context.isSessionContextBias()),
                eq(3)
        ))
                .thenReturn(List.of());

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(
                        KnowledgeBaseDTO.builder().id("kb-1").build(),
                        KnowledgeBaseDTO.builder().id("kb-2").build()
                ));

        tool.knowledgeQuery("新主题", null);

        verify(ragService).retrieve(
                eq(List.of("kb-1", "kb-2")),
                eq("新主题"),
                argThat(context -> context != null
                        && context.isSessionContextBias()
                        && "kb-1".equals(context.getKbId())
                        && "旧文档".equals(context.getSourceName())),
                eq(3)
        );
    }

    @Test
    void shouldIncludeLinkedPdfAssetInStableCitation() {
        RagService ragService = mock(RagService.class);
        RagRetrievalResult evidence = new RagRetrievalResult();
        evidence.setChunkId("chunk-1");
        evidence.setKbId("kb-1");
        evidence.setContent("第 2 页证据");
        evidence.setMetadata("{\"sourceName\":\"设计文档.pdf\",\"contentPath\":\"第 2 页\",\"pageNumber\":2,\"asset\":{\"id\":\"asset-1\",\"type\":\"PDF_PAGE_TEXT\",\"locator\":{\"pageNumber\":2}}}");
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3)).thenReturn(List.of(evidence));

        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").name("研发知识库").build()));

        String response = tool.knowledgeQuery("问题", null);

        assertThat(response)
                .contains("引用: chunk-1")
                .contains("资产: PDF_PAGE_TEXT:asset-1")
                .contains("页码: 2");
    }

    @Test
    void shouldNotPersistLowConfidenceTopResultAsSessionContext() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalResult lowConfidence = new RagRetrievalResult();
        lowConfidence.setChunkId("chunk-1");
        lowConfidence.setKbId("kb-1");
        lowConfidence.setMetadata("{\"sourceName\":\"无关文档\"}");
        lowConfidence.setRrfScore(0.01D);
        lowConfidence.setRank(1);
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3)).thenReturn(List.of(lowConfidence));

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        tool.knowledgeQuery("问题", null);

        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
                .updateRetrievalContext(
                        org.mockito.ArgumentMatchers.eq("session-1"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("7")
                );
    }

    @Test
    void shouldFailClosedWhenTopTwoRrfScoresCannotBeCompared() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalResult top = new RagRetrievalResult();
        top.setChunkId("chunk-1");
        top.setKbId("kb-1");
        top.setMetadata("{\"sourceName\":\"文档\"}");
        top.setRrfScore(0.04D);
        RagRetrievalResult incompleteSecond = new RagRetrievalResult();
        incompleteSecond.setChunkId("chunk-2");
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3))
                .thenReturn(List.of(top, incompleteSecond));

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        tool.knowledgeQuery("问题", null);

        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
                .updateRetrievalContext(
                        org.mockito.ArgumentMatchers.eq("session-1"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("7")
                );
    }

    @Test
    void shouldUseVectorDistanceFallbackWhenDistanceIsNotMapped() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId("chunk-1");
        result.setKbId("kb-1");
        result.setMetadata("{\"sourceName\":\"文档\"}");
        result.setVectorDistance(0.2D);
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3)).thenReturn(List.of(result));

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        tool.knowledgeQuery("问题", null);

        org.mockito.Mockito.verify(sessionService).updateRetrievalContext(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("7")
        );
    }

    @Test
    void shouldPreferVectorDistanceWhenBothDistancesArePresent() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId("chunk-1");
        result.setKbId("kb-1");
        result.setMetadata("{\"sourceName\":\"文档\"}");
        result.setDistance(0.1D);
        result.setVectorDistance(0.8D);
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3)).thenReturn(List.of(result));

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        tool.knowledgeQuery("问题", null);

        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never())
                .updateRetrievalContext(
                        org.mockito.ArgumentMatchers.eq("session-1"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("7")
                );
    }

    @Test
    void shouldUseHighestRrfEvidenceWhenRerankOrderDiffers() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalResult rerankTop = new RagRetrievalResult();
        rerankTop.setChunkId("chunk-low");
        rerankTop.setKbId("kb-1");
        rerankTop.setMetadata("{\"sourceName\":\"低 RRF 文档\"}");
        rerankTop.setRrfScore(0.01D);
        RagRetrievalResult rrfTop = new RagRetrievalResult();
        rrfTop.setChunkId("chunk-high");
        rrfTop.setKbId("kb-1");
        rrfTop.setMetadata("{\"sourceName\":\"高 RRF 文档\"}");
        rrfTop.setRrfScore(0.04D);
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3))
                .thenReturn(List.of(rerankTop, rrfTop));

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        tool.knowledgeQuery("问题", null);

        org.mockito.Mockito.verify(sessionService).updateRetrievalContext(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.argThat(context -> "高 RRF 文档".equals(context.getSourceName())),
                org.mockito.ArgumentMatchers.eq("7")
        );
    }

    @Test
    void shouldSearchAllAuthorizedKnowledgeBasesWhenOnlyHistoricalKnowledgeBaseContextExists() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalContext explicitScope = RagRetrievalContext.builder().kbId("kb-1").build();
        when(sessionService.getRetrievalContext("session-1", "7")).thenReturn(explicitScope);
        when(ragService.retrieve(
                eq(List.of("kb-1", "kb-2")),
                eq("问题"),
                argThat(context -> context != null
                        && context.isSessionContextBias()
                        && "kb-1".equals(context.getKbId())),
                eq(3)
        )).thenReturn(List.of());

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(
                        KnowledgeBaseDTO.builder().id("kb-1").build(),
                        KnowledgeBaseDTO.builder().id("kb-2").build()
                ));

        tool.knowledgeQuery("问题", null);

        verify(ragService).retrieve(
                eq(List.of("kb-1", "kb-2")),
                eq("问题"),
                argThat(context -> context != null
                        && context.isSessionContextBias()
                        && "kb-1".equals(context.getKbId())),
                eq(3)
        );
    }

    @Test
    void shouldIgnoreExplicitSessionScopeWhenKnowledgeBaseIsNoLongerAuthorized() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalContext staleScope = RagRetrievalContext.builder().kbId("kb-revoked").build();
        when(sessionService.getRetrievalContext("session-1", "7")).thenReturn(staleScope);
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3)).thenReturn(List.of());

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        tool.knowledgeQuery("问题", null);

        verify(ragService).retrieve(List.of("kb-1"), "问题", null, 3);
    }

    @Test
    void shouldRejectExternalLookupBeforeRetrievalWhenPermissionIsMissing() {
        RagService ragService = mock(RagService.class);
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        String result = tool.knowledgeQuery("查询最新官方文档", null);

        assertThat(result).contains("许可");
        org.mockito.Mockito.verifyNoInteractions(ragService);
    }

    @Test
    void shouldSkipRetrievalForDirectConversationRoute() {
        RagService ragService = mock(RagService.class);
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        String result = tool.knowledgeQuery("你好", null);

        assertThat(result).contains("闲聊无需检索");
        org.mockito.Mockito.verifyNoInteractions(ragService);
    }

    @Test
    void shouldUseRouterRetrievalLimitForMultimodalQuery() {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve(List.of("kb-1"), "请定位 PDF 第 2 页的表格", null, 5)).thenReturn(List.of());
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        tool.knowledgeQuery("请定位 PDF 第 2 页的表格", null);

        verify(ragService).retrieve(List.of("kb-1"), "请定位 PDF 第 2 页的表格", null, 5);
    }

    @Test
    void shouldMergePdfPageAssetCandidatesAheadOfOrdinaryMultimodalResults() {
        RagService ragService = mock(RagService.class);
        RagRetrievalResult ordinary = new RagRetrievalResult();
        ordinary.setChunkId("chunk-ordinary");
        ordinary.setKbId("kb-1");
        ordinary.setContent("普通文本证据");
        RagRetrievalResult asset = new RagRetrievalResult();
        asset.setChunkId("chunk-pdf-page-2");
        asset.setKbId("kb-1");
        asset.setContent("PDF 第二页表格证据");
        asset.setMetadata("{\"asset\":{\"id\":\"asset-page-2\",\"type\":\"PDF_PAGE_TEXT\"}}");
        when(ragService.retrieve(List.of("kb-1"), "请定位 PDF 第 2 页的表格", null, 5))
                .thenReturn(List.of(ordinary, asset));
        when(ragService.retrievePdfPageAssets(List.of("kb-1"), "请定位 PDF 第 2 页的表格", null, 5))
                .thenReturn(List.of(asset));
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").name("研发知识库").build()));

        String response = tool.knowledgeQuery("请定位 PDF 第 2 页的表格", null);

        verify(ragService).retrievePdfPageAssets(List.of("kb-1"), "请定位 PDF 第 2 页的表格", null, 5);
        assertThat(response)
                .contains("引用: chunk-pdf-page-2")
                .contains("引用: chunk-ordinary")
                .contains("资产: PDF_PAGE_TEXT:asset-page-2");
        assertThat(response.indexOf("引用: chunk-pdf-page-2"))
                .isLessThan(response.indexOf("引用: chunk-ordinary"));
        assertThat(response.indexOf("引用: chunk-pdf-page-2"))
                .isEqualTo(response.lastIndexOf("引用: chunk-pdf-page-2"));
    }

    @Test
    void shouldMergeMarkdownTableAssetCandidatesAheadOfPdfAndOrdinaryMultimodalResults() {
        RagRetrievalResult ordinary = new RagRetrievalResult();
        ordinary.setChunkId("chunk-ordinary");
        ordinary.setKbId("kb-1");
        ordinary.setContent("普通文本证据");
        RagRetrievalResult pdfAsset = new RagRetrievalResult();
        pdfAsset.setChunkId("chunk-pdf-page-2");
        pdfAsset.setKbId("kb-1");
        pdfAsset.setContent("PDF 页文本证据");
        pdfAsset.setMetadata("{\"asset\":{\"id\":\"asset-page-2\",\"type\":\"PDF_PAGE_TEXT\"}}");
        RagRetrievalResult tableAsset = new RagRetrievalResult();
        tableAsset.setChunkId("chunk-markdown-table-2");
        tableAsset.setKbId("kb-1");
        tableAsset.setContent("| 阶段 | 负责人 |\n| --- | --- |\n| G2 | 平台组 |");
        tableAsset.setMetadata("{\"asset\":{\"id\":\"asset-table-2\",\"type\":\"TABLE\",\"locator\":{\"startLine\":12,\"endLine\":14}}}");
        RagService ragService = mock(RagService.class, invocation -> switch (invocation.getMethod().getName()) {
            case "retrieveMarkdownTableAssets" -> List.of(tableAsset);
            case "retrievePdfPageAssets" -> List.of(pdfAsset);
            case "retrieve" -> List.of(ordinary, pdfAsset);
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").name("研发知识库").build()));

        String response = tool.knowledgeQuery("请定位 G2 Markdown 表格", null);

        assertThat(org.mockito.Mockito.mockingDetails(ragService).getInvocations())
                .anyMatch(invocation -> "retrieveMarkdownTableAssets".equals(invocation.getMethod().getName()));
        assertThat(response)
                .contains("引用: chunk-markdown-table-2")
                .contains("资产: TABLE:asset-table-2")
                .contains("行号: 12-14")
                .contains("引用: chunk-pdf-page-2")
                .contains("引用: chunk-ordinary");
        assertThat(response.indexOf("引用: chunk-markdown-table-2"))
                .isLessThan(response.indexOf("引用: chunk-pdf-page-2"));
        assertThat(response.indexOf("引用: chunk-pdf-page-2"))
                .isLessThan(response.indexOf("引用: chunk-ordinary"));
    }

    @Test
    void shouldFallBackToOrdinaryResultsWhenMarkdownTableAssetCandidatesFail() {
        RagRetrievalResult ordinary = new RagRetrievalResult();
        ordinary.setChunkId("chunk-ordinary");
        ordinary.setKbId("kb-1");
        ordinary.setContent("普通文本证据");
        RagService ragService = mock(RagService.class, invocation -> switch (invocation.getMethod().getName()) {
            case "retrieveMarkdownTableAssets" -> throw new IllegalStateException("表格资产候选不可用");
            case "retrievePdfPageAssets" -> List.of();
            case "retrieve" -> List.of(ordinary);
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").name("研发知识库").build()));

        String response = tool.knowledgeQuery("请定位 G2 Markdown 表格", null);

        assertThat(org.mockito.Mockito.mockingDetails(ragService).getInvocations())
                .anyMatch(invocation -> "retrieveMarkdownTableAssets".equals(invocation.getMethod().getName()));
        assertThat(response).contains("引用: chunk-ordinary");
    }

    @Test
    void shouldFallBackToOrdinaryResultsWhenPdfPageAssetCandidatesFail() {
        RagService ragService = mock(RagService.class);
        RagRetrievalResult ordinary = new RagRetrievalResult();
        ordinary.setChunkId("chunk-ordinary");
        ordinary.setKbId("kb-1");
        ordinary.setContent("普通文本证据");
        when(ragService.retrieve(List.of("kb-1"), "请定位 PDF 第 2 页的表格", null, 5))
                .thenReturn(List.of(ordinary));
        when(ragService.retrievePdfPageAssets(List.of("kb-1"), "请定位 PDF 第 2 页的表格", null, 5))
                .thenThrow(new IllegalStateException("资产候选不可用"));
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").name("研发知识库").build()));

        String response = tool.knowledgeQuery("请定位 PDF 第 2 页的表格", null);

        assertThat(response).contains("引用: chunk-ordinary");
    }

    @Test
    void shouldRefuseWhenAuthorizedRetrievalReturnsNoEvidence() {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3)).thenReturn(List.of());
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        String result = tool.knowledgeQuery("问题", null);

        assertThat(result).contains("没有足够证据");
    }

    @Test
    void shouldAbstainSensitiveCredentialRequestBeforeRetrieval() {
        RagService ragService = mock(RagService.class);
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").build()));

        String result = tool.knowledgeQuery("生产数据库的管理员密码是什么？", null);

        assertThat(result).contains("敏感凭据");
        verifyNoInteractions(ragService);
    }

    @Test
    void shouldFormatStableCitationForRetrievedEvidence() {
        RagService ragService = mock(RagService.class);
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId("chunk-1");
        result.setKbId("kb-1");
        result.setContent("证据正文");
        result.setDistance(0.2D);
        result.setMetadata("{\"sourceName\":\"设计文档.pdf\",\"contentPath\":\"第 2 页\",\"pageNumber\":2}");
        when(ragService.retrieve(List.of("kb-1"), "问题", null, 3)).thenReturn(List.of(result));
        KnowledgeTools tool = new KnowledgeTools(ragService, mock(ChatSessionFacadeService.class))
                .fork("7", "session-1", List.of(KnowledgeBaseDTO.builder().id("kb-1").name("研发知识库").build()));

        String response = tool.knowledgeQuery("问题", null);

        assertThat(response)
                .contains("引用: chunk-1")
                .contains("页码: 2")
                .contains("证据正文");
    }
}
