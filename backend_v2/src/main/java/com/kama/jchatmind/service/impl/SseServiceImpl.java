package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

@Service
@Slf4j
public class SseServiceImpl implements SseService {

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_REPLAY_EVENTS = RedisChatSseEventStore.MAX_HISTORY_EVENTS;
    private static final long SESSION_RETENTION_MILLIS = 30 * 60 * 1000L;

    private final ObjectMapper objectMapper;
    private final RedisChatSseEventStore eventStore;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SseServiceImpl(ObjectMapper objectMapper, RedisChatSseEventStore eventStore) {
        this.objectMapper = objectMapper;
        this.eventStore = eventStore;
    }

    @Override
    public SseEmitter connect(String chatSessionId, Long lastEventId) {
        purgeExpiredSessions();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitter.onCompletion(() -> removeClient(chatSessionId, emitter));
        emitter.onTimeout(() -> removeClient(chatSessionId, emitter));
        emitter.onError((error) -> removeClient(chatSessionId, emitter));

        withSessionState(chatSessionId, state -> {
            RedisChatSseEventStore.Snapshot snapshot = eventStore.readSnapshot(chatSessionId);
            reconcileSnapshot(state, chatSessionId, snapshot, true);
            boolean connected = lastEventId == null
                    ? sendInit(chatSessionId, emitter)
                    : replay(chatSessionId, emitter, snapshot, lastEventId);
            if (connected) {
                state.clients.add(emitter);
            }
            return null;
        });
        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        String serializedMessage;
        try {
            serializedMessage = objectMapper.writeValueAsString(message);
        } catch (IOException exception) {
            log.warn("Failed to serialize SSE message for session {}", chatSessionId, exception);
            return;
        }

        purgeExpiredSessions();
        withSessionState(chatSessionId, state -> {
            RedisChatSseEventStore.Event event = eventStore.append(chatSessionId, serializedMessage);
            applyEvent(state, event, true);
            return null;
        });
    }

    boolean replayToEmitter(String chatSessionId, SseEmitter emitter, long lastEventId) {
        purgeExpiredSessions();
        return withSessionState(chatSessionId, state -> {
            RedisChatSseEventStore.Snapshot snapshot = eventStore.readSnapshot(chatSessionId);
            reconcileSnapshot(state, chatSessionId, snapshot, true);
            return replay(chatSessionId, emitter, snapshot, lastEventId);
        });
    }

    void acceptEvent(RedisChatSseEventStore.Event event) {
        purgeExpiredSessions();
        withSessionState(event.sessionId(), state -> {
            applyEvent(state, event, true);
            return null;
        });
    }

    private boolean sendInit(String chatSessionId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
            return true;
        } catch (IOException exception) {
            log.warn("Failed to initialize SSE connection for session {}", chatSessionId, exception);
            return false;
        }
    }

    private boolean replay(
            String chatSessionId,
            SseEmitter emitter,
            RedisChatSseEventStore.Snapshot snapshot,
            long lastEventId
    ) {
        if (snapshot.events().isEmpty()) {
            return sendReplayGap(chatSessionId, emitter, snapshot.latestSequence());
        }
        long oldestSequence = snapshot.events().get(0).sequence();
        long latestSequence = snapshot.latestSequence();
        if (lastEventId < oldestSequence - 1 || lastEventId > latestSequence) {
            return sendReplayGap(chatSessionId, emitter, latestSequence);
        }
        for (RedisChatSseEventStore.Event event : snapshot.events()) {
            if (event.sequence() > lastEventId && !sendMessage(chatSessionId, emitter, toReplayEvent(event))) {
                return false;
            }
        }
        return true;
    }

    private void applyEvent(SessionState state, RedisChatSseEventStore.Event event, boolean broadcast) {
        if (event.sequence() <= state.latestSequence) {
            return;
        }
        if (event.sequence() > state.latestSequence + 1) {
            RedisChatSseEventStore.Snapshot snapshot = eventStore.readSnapshot(event.sessionId());
            reconcileSnapshot(state, event.sessionId(), snapshot, broadcast);
            if (event.sequence() <= state.latestSequence) {
                return;
            }
        }
        ReplayEvent replayEvent = toReplayEvent(event);
        appendHistory(state, replayEvent);
        state.latestSequence = event.sequence();
        if (broadcast) {
            broadcast(event.sessionId(), state, replayEvent);
        }
    }

    private void reconcileSnapshot(
            SessionState state,
            String chatSessionId,
            RedisChatSseEventStore.Snapshot snapshot,
            boolean broadcast
    ) {
        if (snapshot.latestSequence() <= state.latestSequence) {
            return;
        }

        List<RedisChatSseEventStore.Event> unseenEvents = snapshot.events().stream()
                .filter(event -> event.sequence() > state.latestSequence)
                .toList();
        boolean completeRange = !unseenEvents.isEmpty()
                && unseenEvents.get(0).sequence() == state.latestSequence + 1;
        long expectedSequence = state.latestSequence + 1;
        for (RedisChatSseEventStore.Event event : unseenEvents) {
            if (event.sequence() != expectedSequence++) {
                completeRange = false;
                break;
            }
        }
        completeRange = completeRange && expectedSequence - 1 == snapshot.latestSequence();

        if (broadcast && !state.clients.isEmpty()) {
            if (completeRange) {
                for (RedisChatSseEventStore.Event event : unseenEvents) {
                    broadcast(chatSessionId, state, toReplayEvent(event));
                }
            } else {
                for (SseEmitter emitter : new ArrayList<>(state.clients)) {
                    if (!sendReplayGap(chatSessionId, emitter, snapshot.latestSequence())) {
                        state.clients.remove(emitter);
                    }
                }
            }
        }

        state.history.clear();
        for (RedisChatSseEventStore.Event event : snapshot.events()) {
            appendHistory(state, toReplayEvent(event));
        }
        state.latestSequence = snapshot.latestSequence();
    }

    private void appendHistory(SessionState state, ReplayEvent event) {
        state.history.addLast(event);
        while (state.history.size() > MAX_REPLAY_EVENTS) {
            state.history.removeFirst();
        }
    }

    private void broadcast(String chatSessionId, SessionState state, ReplayEvent event) {
        for (SseEmitter emitter : new ArrayList<>(state.clients)) {
            if (!sendMessage(chatSessionId, emitter, event)) {
                state.clients.remove(emitter);
            }
        }
    }

    private ReplayEvent toReplayEvent(RedisChatSseEventStore.Event event) {
        return new ReplayEvent(event.sequence(), event.serializedMessage());
    }

    private boolean sendReplayGap(String chatSessionId, SseEmitter emitter, long latestSequence) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(latestSequence))
                    .name("replay-gap")
                    .data("history_window_exceeded")
            );
            return true;
        } catch (IOException exception) {
            log.warn("Failed to signal SSE replay gap for session {}", chatSessionId, exception);
            return false;
        }
    }

    private boolean sendMessage(String chatSessionId, SseEmitter emitter, ReplayEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name("message")
                    .data(event.serializedMessage())
            );
            return true;
        } catch (IOException exception) {
            log.warn("Failed to send SSE message for session {}", chatSessionId, exception);
            return false;
        }
    }

    private void removeClient(String chatSessionId, SseEmitter emitter) {
        SessionState state = sessions.get(chatSessionId);
        if (state == null) {
            return;
        }
        synchronized (state.lock) {
            if (sessions.get(chatSessionId) == state) {
                state.clients.remove(emitter);
                state.lastActivityAt = System.currentTimeMillis();
            }
        }
    }

    private <T> T withSessionState(String chatSessionId, Function<SessionState, T> operation) {
        while (true) {
            SessionState state = sessions.computeIfAbsent(chatSessionId, ignored -> new SessionState());
            synchronized (state.lock) {
                if (sessions.get(chatSessionId) != state) {
                    continue;
                }
                T result = operation.apply(state);
                state.lastActivityAt = System.currentTimeMillis();
                return result;
            }
        }
    }

    private void purgeExpiredSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_RETENTION_MILLIS;
        sessions.forEach((chatSessionId, state) -> {
            synchronized (state.lock) {
                if (sessions.get(chatSessionId) == state
                        && state.clients.isEmpty()
                        && state.lastActivityAt < cutoff) {
                    sessions.remove(chatSessionId, state);
                }
            }
        });
    }

    static final class SessionState {

        final Object lock = new Object();
        final Set<SseEmitter> clients = new HashSet<>();
        final Deque<ReplayEvent> history = new ArrayDeque<>();
        long latestSequence;
        long lastActivityAt = System.currentTimeMillis();
    }

    private record ReplayEvent(long sequence, String serializedMessage) {
    }
}
