package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageEventFlowIntegrationTest {

    @Test
    void shouldPersistTurnBeforePublishingItsQueueEvent() {
        List<String> writeOrder = new ArrayList<>();
        AtomicReference<ChatEvent> publishedEvent = new AtomicReference<>();
        ChatMessageMapper chatMessageMapper = (ChatMessageMapper) Proxy.newProxyInstance(
                ChatMessageMapper.class.getClassLoader(),
                new Class<?>[]{ChatMessageMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "reserveAgentTurnSequence" -> {
                        writeOrder.add("sequence");
                        yield 4L;
                    }
                    case "insert" -> {
                        writeOrder.add("message");
                        ((ChatMessage) arguments[0]).setId("message-1");
                        yield 1;
                    }
                    case "insertAgentTurnMessageLink" -> {
                        writeOrder.add("turn-link");
                        yield 1;
                    }
                    default -> null;
                }
        );
        ChatMessageConverter converter = new ChatMessageConverter(new ObjectMapper());
        ChatSessionFacadeService sessionService = (ChatSessionFacadeService) Proxy.newProxyInstance(
                ChatSessionFacadeService.class.getClassLoader(),
                new Class<?>[]{ChatSessionFacadeService.class},
                (proxy, method, arguments) -> null
        );
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        ApplicationEventPublisher publisher = event -> {
            writeOrder.add("event");
            publishedEvent.set((ChatEvent) event);
        };
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                chatMessageMapper,
                converter,
                sessionService,
                publisher,
                requestScopeData
        );
        CreateChatMessageRequest request = CreateChatMessageRequest.builder()
                .agentId("agent-1")
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("私有提问")
                .build();

        var response = service.createChatMessage(request);

        assertThat(response.getChatMessageId()).isEqualTo("message-1");
        assertThat(writeOrder).containsExactly("sequence", "message", "turn-link", "event");
        assertThat(publishedEvent.get())
                .extracting(ChatEvent::getUserId, ChatEvent::getAgentId, ChatEvent::getSessionId,
                        ChatEvent::getUserMessageId, ChatEvent::getSequenceNo)
                .containsExactly("7", "agent-1", "session-1", "message-1", 4L);
        assertThat(publishedEvent.get().getUserInput()).isEqualTo("私有提问");
    }
}
