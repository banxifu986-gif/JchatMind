package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KnowledgeToolsTest {

    @Test
    void shouldReadAndWriteSessionRetrievalContext() {
        RecordingChatSessionFacadeService chatSessionFacadeService = new RecordingChatSessionFacadeService();
        chatSessionFacadeService.context = RagRetrievalContext.builder()
                .sourceName("resume.md")
                .contentPath("面试 > 自我介绍")
                .build();

        RecordingRagService ragService = new RecordingRagService();
        KnowledgeTools tools = new KnowledgeTools(ragService, chatSessionFacadeService).fork("user-1", "session-1");

        tools.knowledgeQuery("kb-1", "如何回答");

        assertNotNull(ragService.lastContext);
        assertEquals("resume.md", ragService.lastContext.getSourceName());
        assertEquals("面试 > 自我介绍", ragService.lastContext.getContentPath());
        assertNotNull(chatSessionFacadeService.updatedContext);
        assertEquals("resume.md", chatSessionFacadeService.updatedContext.getSourceName());
        assertEquals("面试 > 自我介绍", chatSessionFacadeService.updatedContext.getContentPath());
    }

    private static class RecordingRagService implements RagService {
        private RagRetrievalContext lastContext;

        @Override
        public float[] embed(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> similaritySearch(String kbId, String title) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> retrieve(String kbId, String query, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> retrieve(String kbId, String query, RagRetrievalContext context, int limit) {
            this.lastContext = context;
            RagRetrievalResult result = new RagRetrievalResult();
            result.setContent("命中内容");
            result.setMetadata("{\"sourceType\":\"md\",\"sourceName\":\"resume.md\",\"contentPath\":\"面试 > 自我介绍 > 如何回答\"}");
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
