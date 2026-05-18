package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatSessionFacadeServiceImpl implements ChatSessionFacadeService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionConverter chatSessionConverter;

    @Override
    public GetChatSessionsResponse getChatSessions(String userId) {
        List<ChatSession> chatSessions = chatSessionMapper.selectByUserId(requireUserId(userId));
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSession chatSession : chatSessions) {
            result.add(toVO(chatSession));
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public GetChatSessionResponse getChatSession(String userId, String chatSessionId) {
        ChatSession chatSession = requireOwnedSession(userId, chatSessionId);
        return GetChatSessionResponse.builder()
                .chatSession(toVO(chatSession))
                .build();
    }

    @Override
    public GetChatSessionsResponse getChatSessionsByAgentId(String userId, String agentId) {
        List<ChatSession> chatSessions = chatSessionMapper.selectByAgentIdAndUserId(
                agentId,
                requireUserId(userId)
        );
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
            request.setUserId(requireUserId(request.getUserId()));
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(request);
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
    public void deleteChatSession(String userId, String chatSessionId) {
        requireOwnedSession(userId, chatSessionId);
        int result = chatSessionMapper.deleteById(chatSessionId);
        if (result <= 0) {
            throw new BizException("删除聊天会话失败");
        }
    }

    @Override
    public void updateChatSession(String userId, String chatSessionId, UpdateChatSessionRequest request) {
        try {
            ChatSession existingChatSession = requireOwnedSession(userId, chatSessionId);
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(existingChatSession);
            chatSessionConverter.updateDTOFromRequest(chatSessionDTO, request);

            ChatSession updatedChatSession = chatSessionConverter.toEntity(chatSessionDTO);
            updatedChatSession.setId(existingChatSession.getId());
            updatedChatSession.setUserId(existingChatSession.getUserId());
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
    public RagRetrievalContext getRetrievalContext(String userId, String chatSessionId) {
        try {
            ChatSession existingChatSession = requireOwnedSession(userId, chatSessionId);
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
    public void updateRetrievalContext(String userId, String chatSessionId, RagRetrievalContext retrievalContext) {
        try {
            ChatSession existingChatSession = requireOwnedSession(userId, chatSessionId);
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(existingChatSession);
            ChatSessionDTO.MetaData metadata = chatSessionDTO.getMetadata();
            if (metadata == null) {
                metadata = new ChatSessionDTO.MetaData();
                chatSessionDTO.setMetadata(metadata);
            }
            metadata.setRetrievalContext(normalizeRetrievalContext(retrievalContext));

            ChatSession updatedChatSession = chatSessionConverter.toEntity(chatSessionDTO);
            updatedChatSession.setId(existingChatSession.getId());
            updatedChatSession.setUserId(existingChatSession.getUserId());
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

    private ChatSession requireOwnedSession(String userId, String chatSessionId) {
        String validatedUserId = requireUserId(userId);
        ChatSession chatSession = chatSessionMapper.selectByIdAndUserId(chatSessionId, validatedUserId);
        if (chatSession == null) {
            throw new BizException("聊天会话不存在: " + chatSessionId);
        }
        return chatSession;
    }

    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException("userId 不能为空");
        }
        return userId.trim();
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
                .sourceType(trimToNull(retrievalContext.getSourceType()))
                .sourceName(trimToNull(retrievalContext.getSourceName()))
                .contentPath(trimToNull(retrievalContext.getContentPath()))
                .build();
        return normalized.hasContext() ? normalized : null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
