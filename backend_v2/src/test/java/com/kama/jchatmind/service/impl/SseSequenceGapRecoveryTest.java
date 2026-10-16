package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SseSequenceGapRecoveryTest {

    @Test
    void shouldReplayRetainedHistoryWhenAcceptedEventSkipsLocalSequence() {
        String sessionId = "session-gap";
        RedisChatSseEventStore.Event firstEvent = new RedisChatSseEventStore.Event(
                sessionId,
                1,
                "missed-event"
        );
        RedisChatSseEventStore.Event resumedEvent = new RedisChatSseEventStore.Event(
                sessionId,
                2,
                "resumed-event"
        );
        RedisChatSseEventStore.Snapshot snapshot = new RedisChatSseEventStore.Snapshot(
                2,
                List.of(firstEvent, resumedEvent)
        );
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper(), new SnapshotEventStore(snapshot));
        RecordingEmitter emitter = new RecordingEmitter();
        SseServiceImpl.SessionState sessionState = new SseServiceImpl.SessionState();
        sessionState.clients.add(emitter);
        sessions(service).put(sessionId, sessionState);

        service.acceptEvent(resumedEvent);

        assertThat(emitter.events).hasSize(2);
        assertThat(emitter.events.get(0)).contains("id:1\n", "missed-event");
        assertThat(emitter.events.get(1)).contains("id:2\n", "resumed-event");
        assertThat(sessionState.latestSequence).isEqualTo(2);
    }

    @Test
    void shouldSignalGapAndResumeWhenRedisHistorySequenceResets() {
        String sessionId = "session-reset";
        RedisChatSseEventStore.Event resetEvent = new RedisChatSseEventStore.Event(
                sessionId,
                1,
                "reset-event"
        );
        RedisChatSseEventStore.Event nextEvent = new RedisChatSseEventStore.Event(
                sessionId,
                2,
                "next-event"
        );
        SnapshotEventStore eventStore = new SnapshotEventStore(new RedisChatSseEventStore.Snapshot(
                1,
                List.of(resetEvent)
        ));
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper(), eventStore);
        RecordingEmitter emitter = new RecordingEmitter();
        SseServiceImpl.SessionState sessionState = new SseServiceImpl.SessionState();
        sessionState.clients.add(emitter);
        sessionState.latestSequence = 70;
        sessions(service).put(sessionId, sessionState);

        service.acceptEvent(resetEvent);

        assertThat(emitter.events).hasSize(1);
        assertThat(emitter.events.get(0)).contains("id:1\n", "event:replay-gap\n");
        eventStore.setSnapshot(new RedisChatSseEventStore.Snapshot(2, List.of(resetEvent, nextEvent)));

        service.acceptEvent(nextEvent);

        assertThat(emitter.events).hasSize(2);
        assertThat(emitter.events.get(1)).contains("id:2\n", "next-event");
        assertThat(sessionState.latestSequence).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, SseServiceImpl.SessionState> sessions(SseServiceImpl service) {
        return (ConcurrentMap<String, SseServiceImpl.SessionState>) ReflectionTestUtils.getField(service, "sessions");
    }

    private static final class SnapshotEventStore extends RedisChatSseEventStore {

        private RedisChatSseEventStore.Snapshot snapshot;

        private SnapshotEventStore(RedisChatSseEventStore.Snapshot snapshot) {
            super(null, new ObjectMapper());
            this.snapshot = snapshot;
        }

        @Override
        public RedisChatSseEventStore.Snapshot readSnapshot(String sessionId) {
            return snapshot;
        }

        private void setSnapshot(RedisChatSseEventStore.Snapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> events = new ArrayList<>();

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            events.add(builder.build().stream()
                    .map(part -> part.getData().toString())
                    .collect(Collectors.joining()));
        }
    }
}
