package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SseServiceImplTest {

    @Test
    void shouldIgnoreSendWhenClientIsNotConnected() {
        SseServiceImpl service = newService();

        assertDoesNotThrow(() -> service.send("session-1", buildMessage()));
    }

    @Test
    void shouldRemoveClientWhenSendingFails() throws IOException {
        SseServiceImpl service = newService();
        SseEmitter failingEmitter = mock(SseEmitter.class);
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        doThrow(new IOException("connection closed"))
                .when(failingEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        SseServiceImpl.SessionState sessionState = new SseServiceImpl.SessionState();
        sessionState.clients.add(failingEmitter);
        sessionState.clients.add(healthyEmitter);
        sessions(service).put("session-1", sessionState);

        assertDoesNotThrow(() -> service.send("session-1", buildMessage()));
        assertThat(sessionState.clients).containsExactly(healthyEmitter);
        verify(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(failingEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldBroadcastMessageToEveryClientConnectedToSession() throws IOException {
        SseServiceImpl service = newService();
        SseEmitter firstEmitter = mock(SseEmitter.class);
        SseEmitter secondEmitter = mock(SseEmitter.class);
        SseServiceImpl.SessionState sessionState = new SseServiceImpl.SessionState();
        sessionState.clients.add(firstEmitter);
        sessionState.clients.add(secondEmitter);
        sessions(service).put("session-1", sessionState);

        service.send("session-1", buildMessage());

        verify(firstEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(secondEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldAssignMonotonicEventIdsToMessages() throws IOException {
        SseServiceImpl service = newService();
        SseEmitter emitter = mock(SseEmitter.class);
        SseServiceImpl.SessionState sessionState = new SseServiceImpl.SessionState();
        sessionState.clients.add(emitter);
        sessions(service).put("session-1", sessionState);

        service.send("session-1", buildMessage());
        service.send("session-1", buildMessage());

        ArgumentCaptor<SseEmitter.SseEventBuilder> events =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(events.capture());

        assertThat(eventText(events.getAllValues().get(0))).contains("id:1\n");
        assertThat(eventText(events.getAllValues().get(1))).contains("id:2\n");
    }

    @Test
    void shouldReplayMessagesAfterLastEventId() throws IOException {
        SseServiceImpl service = newService();
        service.send("session-1", buildMessage());
        service.send("session-1", buildMessage());
        service.send("session-1", buildMessage());
        SseEmitter emitter = mock(SseEmitter.class);

        assertThat(service.replayToEmitter("session-1", emitter, 1)).isTrue();

        ArgumentCaptor<SseEmitter.SseEventBuilder> events =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(events.capture());
        assertThat(eventText(events.getAllValues().get(0))).contains("id:2\n");
        assertThat(eventText(events.getAllValues().get(1))).contains("id:3\n");
    }

    @Test
    void shouldReplayAllRetainedMessagesWhenCursorIsImmediatelyBeforeHistory() throws IOException {
        SseServiceImpl service = newService();
        for (int i = 0; i < 70; i++) {
            service.send("session-1", buildMessage());
        }
        SseEmitter emitter = mock(SseEmitter.class);

        assertThat(service.replayToEmitter("session-1", emitter, 6)).isTrue();

        ArgumentCaptor<SseEmitter.SseEventBuilder> events =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(64)).send(events.capture());
        assertThat(eventText(events.getAllValues().get(0))).contains("id:7\n");
        assertThat(eventText(events.getAllValues().get(63))).contains("id:70\n");
    }

    @Test
    void shouldSignalGapWhenCursorPrecedesRetainedHistory() throws IOException {
        SseServiceImpl service = newService();
        for (int i = 0; i < 70; i++) {
            service.send("session-1", buildMessage());
        }
        SseEmitter emitter = mock(SseEmitter.class);

        assertThat(service.replayToEmitter("session-1", emitter, 0)).isTrue();

        ArgumentCaptor<SseEmitter.SseEventBuilder> event =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(event.capture());
        assertThat(eventText(event.getValue()))
                .contains("id:70\n", "event:replay-gap\n", "history_window_exceeded");
    }

    @Test
    void shouldKeepMultipleEmittersForSameSession() {
        SseServiceImpl service = newService();

        SseEmitter first = service.connect("session-1", null);
        SseEmitter second = service.connect("session-1", null);

        assertThat(sessions(service).get("session-1").clients).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void shouldExposeErrorEventTypeForFailedAgentRuns() {
        assertEquals(SseMessage.Type.AI_ERROR, SseMessage.Type.valueOf("AI_ERROR"));
    }

    private static SseServiceImpl newService() {
        ObjectMapper objectMapper = new ObjectMapper();
        RedisChatSseEventStore eventStore = mock(RedisChatSseEventStore.class);
        Map<String, AtomicLong> sequences = new HashMap<>();
        Map<String, List<RedisChatSseEventStore.Event>> eventsBySession = new HashMap<>();
        when(eventStore.append(anyString(), anyString())).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            String serializedMessage = invocation.getArgument(1);
            long sequence = sequences.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
            List<RedisChatSseEventStore.Event> events = eventsBySession.computeIfAbsent(
                    sessionId,
                    ignored -> new ArrayList<>()
            );
            RedisChatSseEventStore.Event event = new RedisChatSseEventStore.Event(
                    sessionId,
                    sequence,
                    serializedMessage
            );
            events.add(event);
            if (events.size() > RedisChatSseEventStore.MAX_HISTORY_EVENTS) {
                events.remove(0);
            }
            return event;
        });
        when(eventStore.readSnapshot(anyString())).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            long latestSequence = sequences.getOrDefault(sessionId, new AtomicLong()).get();
            List<RedisChatSseEventStore.Event> events = eventsBySession.getOrDefault(sessionId, List.of());
            return new RedisChatSseEventStore.Snapshot(latestSequence, List.copyOf(events));
        });
        return new SseServiceImpl(objectMapper, eventStore);
    }

    private static SseMessage buildMessage() {
        return SseMessage.builder()
                .type(SseMessage.Type.AI_THINKING)
                .payload(SseMessage.Payload.builder()
                        .statusText("thinking")
                        .done(false)
                        .stepNumber(1)
                        .build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, SseServiceImpl.SessionState> sessions(SseServiceImpl service) {
        return (ConcurrentMap<String, SseServiceImpl.SessionState>) ReflectionTestUtils.getField(service, "sessions");
    }

    private static String eventText(SseEmitter.SseEventBuilder event) {
        return event.build().stream()
                .map(part -> part.getData().toString())
                .collect(Collectors.joining());
    }
}
