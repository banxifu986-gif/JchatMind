package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.event.listener.ChatEventListener;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageEventFlowIntegrationTest {

    @Test
    void shouldPersistMessageDispatchAgentAndReturnSessionHistory() throws Exception {
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ChatMessageConverter chatMessageConverter = mock(ChatMessageConverter.class);
        ChatSessionFacadeService chatSessionFacadeService = mock(ChatSessionFacadeService.class);
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        List<ChatMessage> storedMessages = new ArrayList<>();
        CreateChatMessageRequest request = CreateChatMessageRequest.builder()
                .agentId("agent-1")
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("请总结这段内容")
                .build();
        ChatMessageDTO messageDTO = ChatMessageDTO.builder()
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("请总结这段内容")
                .build();
        when(chatMessageConverter.toDTO(request)).thenReturn(messageDTO);
        when(chatMessageConverter.toEntity(messageDTO)).thenReturn(ChatMessage.builder()
                .id("message-1")
                .sessionId("session-1")
                .role("user")
                .content("请总结这段内容")
                .build());
        when(chatMessageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
            storedMessages.add(invocation.getArgument(0));
            return 1;
        });
        when(chatMessageMapper.selectBySessionId("session-1")).thenAnswer(invocation -> List.copyOf(storedMessages));
        when(chatMessageConverter.toVO(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage chatMessage = invocation.getArgument(0);
            return ChatMessageVO.builder()
                    .id(chatMessage.getId())
                    .sessionId(chatMessage.getSessionId())
                    .role(ChatMessageDTO.RoleType.fromRole(chatMessage.getRole()))
                    .content(chatMessage.getContent())
                    .build();
        });
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ChatMessageMapper.class, () -> chatMessageMapper);
            context.registerBean(ChatMessageConverter.class, () -> chatMessageConverter);
            context.registerBean(ChatSessionFacadeService.class, () -> chatSessionFacadeService);
            context.getBeanFactory().registerSingleton("requestScopeData", requestScopeData);
            context.registerBean(JChatMindFactory.class, () -> jChatMindFactory);
            context.registerBean(UserMemoryFacadeService.class, () -> userMemoryFacadeService);
            context.registerBean(ChatEventListener.class);
            context.registerBean(ChatMessageFacadeServiceImpl.class);
            context.refresh();

            ChatMessageFacadeServiceImpl service = context.getBean(ChatMessageFacadeServiceImpl.class);
            service.createChatMessage(request);
            GetChatMessagesResponse history = service.getChatMessagesBySessionId("session-1");

            assertThat(history.getChatMessages())
                    .extracting(ChatMessageVO::getId, ChatMessageVO::getContent)
                    .containsExactly(tuple("message-1", "请总结这段内容"));
            verify(jChatMindFactory).create("7", "agent-1", "session-1");
            verify(agent).run();
            verify(userMemoryFacadeService).extractMemoryCandidates("7", "session-1");
        }
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
