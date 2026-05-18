package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.UpdateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String userId, String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String userId, String sessionId, int limit);

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(String userId, ChatMessageDTO chatMessageDTO);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String userId, String chatMessageId, String appendContent);

    void deleteChatMessage(String userId, String chatMessageId);

    void updateChatMessage(String userId, String chatMessageId, UpdateChatMessageRequest request);
}
