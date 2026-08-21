package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
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
}
