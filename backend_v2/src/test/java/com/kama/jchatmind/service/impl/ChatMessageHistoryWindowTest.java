package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageHistoryWindowTest {

    private static final String SESSION_ID = "session-history-window";

    @Test
    void shouldReturnPersistentHistoryBeyondRedisSseWindow() {
        List<ChatMessage> storedMessages = IntStream.range(0, 70)
                .mapToObj(index -> ChatMessage.builder()
                        .id("message-" + index)
                        .sessionId(SESSION_ID)
                        .role(index % 2 == 0 ? "user" : "assistant")
                        .content("message content " + index)
                        .build())
                .toList();
        AtomicInteger historyQueryCount = new AtomicInteger();
        ChatMessageMapper chatMessageMapper = mapper(storedMessages, historyQueryCount);
        ChatSessionFacadeService chatSessionFacadeService = sessionService();
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                chatMessageMapper,
                new ChatMessageConverter(new ObjectMapper()),
                chatSessionFacadeService,
                (ApplicationEventPublisher) event -> { },
                requestScopeData
        );

        var response = service.getChatMessagesBySessionId(SESSION_ID);

        assertThat(historyQueryCount).hasValue(1);
        assertThat(response.getChatMessages()).hasSize(70);
        assertThat(response.getChatMessages()[0].getContent()).isEqualTo("message content 0");
        assertThat(response.getChatMessages()[69].getContent()).isEqualTo("message content 69");
    }

    @Test
    void shouldKeepSessionHistoryMapperQueryUnboundedAndChronological() throws Exception {
        try (InputStream mapperXml = getClass().getResourceAsStream("/mapper/ChatMessageMapper.xml")) {
            assertThat(mapperXml).isNotNull();
            String mapperSource = new String(mapperXml.readAllBytes(), StandardCharsets.UTF_8);
            int queryStart = mapperSource.indexOf("<select id=\"selectBySessionId\"");
            int queryEnd = mapperSource.indexOf("</select>", queryStart);

            assertThat(queryStart).isGreaterThanOrEqualTo(0);
            assertThat(queryEnd).isGreaterThan(queryStart);
            String query = mapperSource.substring(queryStart, queryEnd).toLowerCase(Locale.ROOT);
            assertThat(query)
                    .contains("order by turn.sequence_no asc nulls first, message.created_at asc")
                    .doesNotContain("limit");
        }
    }

    @Test
    void shouldBoundQueuedAgentHistoryAtItsUserMessage() throws Exception {
        try (InputStream mapperXml = getClass().getResourceAsStream("/mapper/ChatMessageMapper.xml")) {
            assertThat(mapperXml).isNotNull();
            String mapperSource = new String(mapperXml.readAllBytes(), StandardCharsets.UTF_8);
            int queryStart = mapperSource.indexOf(
                    "<select id=\"selectBySessionIdRecentlyThroughMessageId\""
            );
            int queryEnd = mapperSource.indexOf("</select>", queryStart);

            assertThat(queryStart).isGreaterThanOrEqualTo(0);
            assertThat(queryEnd).isGreaterThan(queryStart);
            String query = mapperSource.substring(queryStart, queryEnd).toLowerCase(Locale.ROOT);
            assertThat(query)
                    .contains("agent_turn_sequence")
                    .contains("#{messageid}")
                    .contains("role = 'user'")
                    .contains("order by turn.sequence_no desc nulls last, message.created_at desc, message.id desc")
                    .contains("limit #{limit}");
        }
    }

    @Test
    void shouldAskMapperForHistoryThroughTheQueuedUserMessage() {
        ChatMessage queuedMessage = ChatMessage.builder()
                .id("queued-message")
                .sessionId(SESSION_ID)
                .role("user")
                .content("queued input")
                .build();
        AtomicReference<String> throughMessageId = new AtomicReference<>();
        AtomicInteger limit = new AtomicInteger();
        ChatMessageMapper chatMessageMapper = (ChatMessageMapper) Proxy.newProxyInstance(
                ChatMessageMapper.class.getClassLoader(),
                new Class<?>[]{ChatMessageMapper.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("selectBySessionIdRecentlyThroughMessageId")) {
                        throughMessageId.set((String) arguments[1]);
                        limit.set((int) arguments[2]);
                        return List.of(queuedMessage);
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return null;
                }
        );
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                chatMessageMapper,
                new ChatMessageConverter(new ObjectMapper()),
                sessionService(),
                (ApplicationEventPublisher) event -> { },
                requestScopeData
        );

        List<com.kama.jchatmind.model.dto.ChatMessageDTO> result = service
                .getChatMessagesBySessionIdRecently(SESSION_ID, 8, "7", "queued-message");

        assertThat(throughMessageId.get()).isEqualTo("queued-message");
        assertThat(limit).hasValue(8);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("queued input");
    }

    private ChatMessageMapper mapper(List<ChatMessage> storedMessages, AtomicInteger historyQueryCount) {
        return (ChatMessageMapper) Proxy.newProxyInstance(
                ChatMessageMapper.class.getClassLoader(),
                new Class<?>[]{ChatMessageMapper.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("selectBySessionId")) {
                        historyQueryCount.incrementAndGet();
                        return storedMessages;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                }
        );
    }

    private ChatSessionFacadeService sessionService() {
        return (ChatSessionFacadeService) Proxy.newProxyInstance(
                ChatSessionFacadeService.class.getClassLoader(),
                new Class<?>[]{ChatSessionFacadeService.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getChatSession") && arguments.length == 1) {
                        return GetChatSessionResponse.builder()
                                .chatSession(ChatSessionVO.builder()
                                        .id(SESSION_ID)
                                        .userId("7")
                                        .build())
                                .build();
                    }
                    return null;
                }
        );
    }
}
