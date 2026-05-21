package com.kama.jchatmind.rag;

import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiKnowledgeBaseRetrievalFlowTest {

    @Test
    void shouldDefaultToSessionScopedKbWhenNoKbIdsSpecified() {
        RecordingChatSessionFacadeService chatSessionFacadeService = new RecordingChatSessionFacadeService();
        chatSessionFacadeService.context = RagRetrievalContext.builder()
                .kbId("kb-a")
                .sourceName("resume-a.md")
                .contentPath("面试 > 自我介绍")
                .build();

        RecordingRagService ragService = new RecordingRagService();
        KnowledgeTools tools = new KnowledgeTools(ragService, chatSessionFacadeService).fork(
                "user-1",
                "session-1",
                List.of(kb("kb-a", "简历库A"), kb("kb-b", "简历库B"))
        );

        String result = tools.knowledgeQuery("这一段怎么讲", null);

        assertEquals(List.of("kb-a"), ragService.calls.get(0).kbIds());
        assertEquals("kb-a", ragService.calls.get(0).context().getKbId());
        assertTrue(result.contains("知识库: 简历库A"));
    }

    @Test
    void shouldRestrictToExplicitSubsetWhenKbIdsProvided() {
        RecordingChatSessionFacadeService chatSessionFacadeService = new RecordingChatSessionFacadeService();
        chatSessionFacadeService.context = RagRetrievalContext.builder()
                .kbId("kb-a")
                .sourceName("resume-a.md")
                .contentPath("面试 > 自我介绍")
                .build();

        RecordingRagService ragService = new RecordingRagService();
        KnowledgeTools tools = new KnowledgeTools(ragService, chatSessionFacadeService).fork(
                "user-1",
                "session-1",
                List.of(kb("kb-a", "简历库A"), kb("kb-b", "简历库B"))
        );

        String result = tools.knowledgeQuery("Java 内存模型", List.of("kb-b"));

        assertEquals(List.of("kb-b"), ragService.calls.get(0).kbIds());
        assertEquals(null, ragService.calls.get(0).context());
        assertTrue(result.contains("知识库: 简历库B"));
    }

    @Test
    void shouldPersistTop1KbIntoSessionContext() {
        RecordingChatSessionFacadeService chatSessionFacadeService = new RecordingChatSessionFacadeService();
        RecordingRagService ragService = new RecordingRagService();
        KnowledgeTools tools = new KnowledgeTools(ragService, chatSessionFacadeService).fork(
                "user-1",
                "session-1",
                List.of(kb("kb-a", "简历库A"), kb("kb-b", "简历库B"))
        );

        tools.knowledgeQuery("项目亮点", List.of("kb-b"));

        assertNotNull(chatSessionFacadeService.updatedContext);
        assertEquals("kb-b", chatSessionFacadeService.updatedContext.getKbId());
        assertEquals("resume-b.md", chatSessionFacadeService.updatedContext.getSourceName());
        assertEquals("面试 > 项目亮点", chatSessionFacadeService.updatedContext.getContentPath());
    }

    private static KnowledgeBaseDTO kb(String id, String name) {
        return KnowledgeBaseDTO.builder()
                .id(id)
                .name(name)
                .build();
    }

    private record RetrievalCall(List<String> kbIds, RagRetrievalContext context) {
    }

    private static class RecordingRagService implements RagService {
        private final List<RetrievalCall> calls = new ArrayList<>();

        @Override
        public float[] embed(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> similaritySearch(List<String> kbIds, String title) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> retrieve(
                List<String> kbIds,
                String query,
                RagRetrievalContext context,
                int limit
        ) {
            calls.add(new RetrievalCall(kbIds, context));
            RagRetrievalResult result = new RagRetrievalResult();
            result.setKbId(kbIds.get(0));
            result.setContent("命中内容");
            if ("kb-b".equals(kbIds.get(0))) {
                result.setMetadata("{\"sourceType\":\"md\",\"sourceName\":\"resume-b.md\",\"contentPath\":\"面试 > 项目亮点 > 项目亮点\"}");
            } else {
                result.setMetadata("{\"sourceType\":\"md\",\"sourceName\":\"resume-a.md\",\"contentPath\":\"面试 > 自我介绍 > 这一段怎么讲\"}");
            }
            return List.of(result);
        }
    }

    private static class RecordingChatSessionFacadeService implements ChatSessionFacadeService {
        private RagRetrievalContext context;
        private RagRetrievalContext updatedContext;

        @Override
        public com.kama.jchatmind.model.response.GetChatSessionsResponse getChatSessions(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.kama.jchatmind.model.response.GetChatSessionResponse getChatSession(String userId, String chatSessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.kama.jchatmind.model.response.GetChatSessionsResponse getChatSessionsByAgentId(String userId, String agentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.kama.jchatmind.model.response.CreateChatSessionResponse createChatSession(com.kama.jchatmind.model.request.CreateChatSessionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteChatSession(String userId, String chatSessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateChatSession(String userId, String chatSessionId, com.kama.jchatmind.model.request.UpdateChatSessionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RagRetrievalContext getRetrievalContext(String userId, String chatSessionId) {
            return context;
        }

        @Override
        public void updateRetrievalContext(String userId, String chatSessionId, RagRetrievalContext retrievalContext) {
            this.updatedContext = retrievalContext;
        }
    }
}
