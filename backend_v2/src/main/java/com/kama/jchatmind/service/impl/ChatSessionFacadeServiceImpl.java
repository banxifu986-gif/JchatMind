package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.UpdateChatSessionRequest;
import com.kama.jchatmind.model.response.CreateChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionsResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatSessionFacadeServiceImpl implements ChatSessionFacadeService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionConverter chatSessionConverter;
    private final RequestScopeData requestScopeData;

    @Override
    public GetChatSessionsResponse getChatSessions() {
        String userId = requireUserId();
        List<ChatSession> chatSessions = chatSessionMapper.selectByUserId(userId);
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSession chatSession : chatSessions) {
            result.add(toVO(chatSession));
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public GetChatSessionResponse getChatSession(String chatSessionId) {
        ChatSession chatSession = requireOwnedSession(chatSessionId);
        return GetChatSessionResponse.builder()
                .chatSession(toVO(chatSession))
                .build();
    }

    @Override
    public GetChatSessionsResponse getChatSessionsByAgentId(String agentId) {
        String userId = requireUserId();
        List<ChatSession> chatSessions = chatSessionMapper.selectByAgentIdAndUserId(agentId, userId);
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSession chatSession : chatSessions) {
            result.add(toVO(chatSession));
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public CreateChatSessionResponse createChatSession(CreateChatSessionRequest request) {
        try {
            String userId = requireUserId();
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(request);
            chatSessionDTO.setUserId(userId);
            ChatSession chatSession = chatSessionConverter.toEntity(chatSessionDTO);
            LocalDateTime now = LocalDateTime.now();
            chatSession.setCreatedAt(now);
            chatSession.setUpdatedAt(now);
            int result = chatSessionMapper.insert(chatSession);
            if (result <= 0) {
                throw new BizException("创建聊天会话失败");
            }
            return CreateChatSessionResponse.builder()
                    .chatSessionId(chatSession.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建聊天会话时序列化失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteChatSession(String chatSessionId) {
        requireOwnedSession(chatSessionId);
        int result = chatSessionMapper.deleteById(chatSessionId);
        if (result <= 0) {
            throw new BizException("删除聊天会话失败");
        }
    }

    @Override
    public void updateChatSession(String chatSessionId, UpdateChatSessionRequest request) {
        try {
            String userId = requireUserId();
            ChatSession existingChatSession = requireOwnedSession(chatSessionId);
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(existingChatSession);
            chatSessionConverter.updateDTOFromRequest(chatSessionDTO, request);

            ChatSession updatedChatSession = chatSessionConverter.toEntity(chatSessionDTO);
            updatedChatSession.setId(existingChatSession.getId());
            updatedChatSession.setUserId(userId);
            updatedChatSession.setAgentId(existingChatSession.getAgentId());
            updatedChatSession.setCreatedAt(existingChatSession.getCreatedAt());
            updatedChatSession.setUpdatedAt(LocalDateTime.now());

            int result = chatSessionMapper.updateById(updatedChatSession);
            if (result <= 0) {
                throw new BizException("更新聊天会话失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新聊天会话时序列化失败: " + e.getMessage());
        }
    }

    @Override
    public RagRetrievalContext getRetrievalContext(String chatSessionId) {
        try {
            ChatSession existingChatSession = requireOwnedSession(chatSessionId);
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(existingChatSession);
            if (chatSessionDTO.getMetadata() == null) {
                return null;
            }
            return chatSessionDTO.getMetadata().getRetrievalContext();
        } catch (JsonProcessingException e) {
            throw new BizException("读取会话检索上下文失败: " + e.getMessage());
        }
    }

    @Override
    public void updateRetrievalContext(String chatSessionId, RagRetrievalContext retrievalContext) {
        try {
            String userId = requireUserId();
            ChatSession existingChatSession = requireOwnedSession(chatSessionId);
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(existingChatSession);
            ChatSessionDTO.MetaData metadata = chatSessionDTO.getMetadata();
            if (metadata == null) {
                metadata = new ChatSessionDTO.MetaData();
                chatSessionDTO.setMetadata(metadata);
            }
            metadata.setRetrievalContext(normalizeRetrievalContext(retrievalContext));

            ChatSession updatedChatSession = chatSessionConverter.toEntity(chatSessionDTO);
            updatedChatSession.setId(existingChatSession.getId());
            updatedChatSession.setUserId(userId);
            updatedChatSession.setAgentId(existingChatSession.getAgentId());
            updatedChatSession.setCreatedAt(existingChatSession.getCreatedAt());
            updatedChatSession.setUpdatedAt(LocalDateTime.now());

            int result = chatSessionMapper.updateById(updatedChatSession);
            if (result <= 0) {
                throw new BizException("更新会话检索上下文失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新会话检索上下文失败: " + e.getMessage());
        }
    }

    private ChatSession requireOwnedSession(String chatSessionId) {
        String userId = requireUserId();
        ChatSession chatSession = chatSessionMapper.selectByIdAndUserId(chatSessionId, userId);
        if (chatSession == null) {
            throw new BizException("聊天会话不存在: " + chatSessionId);
        }
        return chatSession;
    }

    private String requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        return String.valueOf(userId);
    }

    private ChatSessionVO toVO(ChatSession chatSession) {
        try {
            return chatSessionConverter.toVO(chatSession);
        } catch (JsonProcessingException e) {
            throw new BizException("聊天会话反序列化失败: " + e.getMessage());
        }
    }

    private RagRetrievalContext normalizeRetrievalContext(RagRetrievalContext retrievalContext) {
        if (retrievalContext == null) {
            return null;
        }
        RagRetrievalContext normalized = RagRetrievalContext.builder()
                .kbId(trimToNull(retrievalContext.getKbId()))
                .sourceType(trimToNull(retrievalContext.getSourceType()))
                .sourceName(trimToNull(retrievalContext.getSourceName()))
                .contentPath(trimToNull(retrievalContext.getContentPath()))
                .build();
        return normalized.hasContext() ? normalized : null;
    }

    private String trimToNull(String value) {
        return org.springframework.util.StringUtils.hasText(value) ? value.trim() : null;
    }
}
