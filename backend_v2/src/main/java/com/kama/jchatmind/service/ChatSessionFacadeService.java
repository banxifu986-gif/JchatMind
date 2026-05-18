package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.UpdateChatSessionRequest;
import com.kama.jchatmind.model.response.CreateChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionsResponse;

public interface ChatSessionFacadeService {
    GetChatSessionsResponse getChatSessions(String userId);

    GetChatSessionResponse getChatSession(String userId, String chatSessionId);

    GetChatSessionsResponse getChatSessionsByAgentId(String userId, String agentId);

    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    void deleteChatSession(String userId, String chatSessionId);

    void updateChatSession(String userId, String chatSessionId, UpdateChatSessionRequest request);

    RagRetrievalContext getRetrievalContext(String userId, String chatSessionId);

    void updateRetrievalContext(String userId, String chatSessionId, RagRetrievalContext retrievalContext);
}
