package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.UpdateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatMessageFacadeServiceImpl implements ChatMessageFacadeService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;
    private final ChatSessionFacadeService chatSessionFacadeService;
    private final ApplicationEventPublisher publisher;

    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String userId, String sessionId) {
        requireOwnedSession(userId, sessionId);
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            result.add(toVO(chatMessage));
        }
        return GetChatMessagesResponse.builder()
                .chatMessages(result.toArray(new ChatMessageVO[0]))
                .build();
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String userId, String sessionId, int limit) {
        requireOwnedSession(userId, sessionId);
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionIdRecently(sessionId, limit);
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            result.add(toDTO(chatMessage));
        }
        return result;
    }

    @Override
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        String userId = requireUserId(request.getUserId());
        requireOwnedSession(userId, request.getSessionId());
        ChatMessage chatMessage = doCreateChatMessage(request);
        publisher.publishEvent(new ChatEvent(
                userId,
                request.getAgentId(),
                chatMessage.getSessionId(),
                chatMessage.getContent()
        ));
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse createChatMessage(String userId, ChatMessageDTO chatMessageDTO) {
        requireOwnedSession(userId, chatMessageDTO.getSessionId());
        ChatMessage chatMessage = doCreateChatMessage(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        requireOwnedSession(request.getUserId(), request.getSessionId());
        ChatMessage chatMessage = doCreateChatMessage(request);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String userId, String chatMessageId, String appendContent) {
        ChatMessage existingChatMessage = requireOwnedMessage(userId, chatMessageId);
        String currentContent = existingChatMessage.getContent() != null ? existingChatMessage.getContent() : "";
        String updatedContent = currentContent + appendContent;

        ChatMessage updatedChatMessage = ChatMessage.builder()
                .id(existingChatMessage.getId())
                .sessionId(existingChatMessage.getSessionId())
                .role(existingChatMessage.getRole())
                .content(updatedContent)
                .metadata(existingChatMessage.getMetadata())
                .createdAt(existingChatMessage.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = chatMessageMapper.updateById(updatedChatMessage);
        if (result <= 0) {
            throw new BizException("追加聊天消息失败");
        }

        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessageId)
                .build();
    }

    @Override
    public void deleteChatMessage(String userId, String chatMessageId) {
        requireOwnedMessage(userId, chatMessageId);
        int result = chatMessageMapper.deleteById(chatMessageId);
        if (result <= 0) {
            throw new BizException("删除聊天消息失败");
        }
    }

    @Override
    public void updateChatMessage(String userId, String chatMessageId, UpdateChatMessageRequest request) {
        try {
            ChatMessage existingChatMessage = requireOwnedMessage(userId, chatMessageId);
            ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(existingChatMessage);
            chatMessageConverter.updateDTOFromRequest(chatMessageDTO, request);

            ChatMessage updatedChatMessage = chatMessageConverter.toEntity(chatMessageDTO);
            updatedChatMessage.setId(existingChatMessage.getId());
            updatedChatMessage.setSessionId(existingChatMessage.getSessionId());
            updatedChatMessage.setRole(existingChatMessage.getRole());
            updatedChatMessage.setCreatedAt(existingChatMessage.getCreatedAt());
            updatedChatMessage.setUpdatedAt(LocalDateTime.now());

            int result = chatMessageMapper.updateById(updatedChatMessage);
            if (result <= 0) {
                throw new BizException("更新聊天消息失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新聊天消息时序列化失败: " + e.getMessage());
        }
    }

    private ChatMessage doCreateChatMessage(CreateChatMessageRequest request) {
        ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(request);
        return doCreateChatMessage(chatMessageDTO);
    }

    private ChatMessage doCreateChatMessage(ChatMessageDTO chatMessageDTO) {
        try {
            ChatMessage chatMessage = chatMessageConverter.toEntity(chatMessageDTO);
            LocalDateTime now = LocalDateTime.now();
            chatMessage.setCreatedAt(now);
            chatMessage.setUpdatedAt(now);
            int result = chatMessageMapper.insert(chatMessage);
            if (result <= 0) {
                throw new BizException("创建聊天消息失败");
            }
            return chatMessage;
        } catch (JsonProcessingException e) {
            throw new BizException("创建聊天消息时序列化失败: " + e.getMessage());
        }
    }

    private void requireOwnedSession(String userId, String sessionId) {
        chatSessionFacadeService.getChatSession(requireUserId(userId), sessionId);
    }

    private ChatMessage requireOwnedMessage(String userId, String chatMessageId) {
        ChatMessage chatMessage = chatMessageMapper.selectById(chatMessageId);
        if (chatMessage == null) {
            throw new BizException("聊天消息不存在: " + chatMessageId);
        }
        requireOwnedSession(userId, chatMessage.getSessionId());
        return chatMessage;
    }

    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException("userId 不能为空");
        }
        return userId.trim();
    }

    private ChatMessageVO toVO(ChatMessage chatMessage) {
        try {
            return chatMessageConverter.toVO(chatMessage);
        } catch (JsonProcessingException e) {
            throw new BizException("聊天消息反序列化失败: " + e.getMessage());
        }
    }

    private ChatMessageDTO toDTO(ChatMessage chatMessage) {
        try {
            return chatMessageConverter.toDTO(chatMessage);
        } catch (JsonProcessingException e) {
            throw new BizException("聊天消息反序列化失败: " + e.getMessage());
        }
    }
}
