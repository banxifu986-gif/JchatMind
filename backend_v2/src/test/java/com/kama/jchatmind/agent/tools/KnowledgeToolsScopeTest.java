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

        assertThat(result).isEmpty();
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
        when(ragService.retrieve(List.of("kb-1", "kb-2"), "新主题", previousContext, 3))
                .thenReturn(List.of());

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(
                        KnowledgeBaseDTO.builder().id("kb-1").build(),
                        KnowledgeBaseDTO.builder().id("kb-2").build()
                ));

        tool.knowledgeQuery("新主题", null);

        verify(ragService).retrieve(List.of("kb-1", "kb-2"), "新主题", previousContext, 3);
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
    void shouldHonorExplicitSessionKnowledgeBaseScopeWithoutSourceContext() {
        RagService ragService = mock(RagService.class);
        ChatSessionFacadeService sessionService = mock(ChatSessionFacadeService.class);
        RagRetrievalContext explicitScope = RagRetrievalContext.builder().kbId("kb-1").build();
        when(sessionService.getRetrievalContext("session-1", "7")).thenReturn(explicitScope);
        when(ragService.retrieve(List.of("kb-1"), "问题", explicitScope, 3)).thenReturn(List.of());

        KnowledgeTools tool = new KnowledgeTools(ragService, sessionService)
                .fork("7", "session-1", List.of(
                        KnowledgeBaseDTO.builder().id("kb-1").build(),
                        KnowledgeBaseDTO.builder().id("kb-2").build()
                ));

        tool.knowledgeQuery("问题", null);

        verify(ragService).retrieve(List.of("kb-1"), "问题", explicitScope, 3);
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
}
