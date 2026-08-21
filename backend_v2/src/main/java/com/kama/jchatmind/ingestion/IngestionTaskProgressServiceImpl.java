package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.entity.IngestionTask;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@AllArgsConstructor
@Slf4j
public class IngestionTaskProgressServiceImpl implements IngestionTaskProgressService {

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_REPLAY_EVENTS = 64;
    private static final long TERMINAL_EVENT_RETENTION_MILLIS = 30 * 60 * 1000L;
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "DEAD_LETTER", "CANCELLED");

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IngestionTaskProgressEvent> latestEvents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<IngestionTaskProgressEvent>> eventHistory = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> latestEventTimes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> taskLocks = new ConcurrentHashMap<>();

    @Override
    public SseEmitter connect(IngestionTask task) {
        return connect(task, null);
    }

    @Override
    public SseEmitter connect(IngestionTask task, Long lastEventId) {
        purgeExpiredTerminalEvents();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        String taskId = task.getId();
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(error -> removeEmitter(taskId, emitter));
        synchronized (lockFor(taskId)) {
            boolean connected;
            if (lastEventId == null) {
                IngestionTaskProgressEvent latest = latestEvents.computeIfAbsent(taskId, ignored -> {
                    IngestionTaskProgressEvent initial = nextEvent(task);
                    remember(taskId, initial);
                    return initial;
                });
                connected = send(taskId, emitter, latest);
            } else {
                connected = replay(taskId, emitter, lastEventId);
            }
            if (connected) {
                emitters.computeIfAbsent(taskId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
            }
        }
        return emitter;
    }

    @Override
    public void publish(IngestionTask task) {
        purgeExpiredTerminalEvents();
        String taskId = task.getId();
        synchronized (lockFor(taskId)) {
            IngestionTaskProgressEvent event = nextEvent(task);
            latestEvents.put(taskId, event);
            remember(taskId, event);
            Set<SseEmitter> taskEmitters = emitters.get(taskId);
            if (taskEmitters != null) {
                for (SseEmitter emitter : taskEmitters) {
                    send(taskId, emitter, event);
                }
            }
        }
    }

    private void remember(String taskId, IngestionTaskProgressEvent event) {
        Deque<IngestionTaskProgressEvent> history = eventHistory.computeIfAbsent(
                taskId, ignored -> new ConcurrentLinkedDeque<>()
        );
        history.addLast(event);
        while (history.size() > MAX_REPLAY_EVENTS) {
            history.pollFirst();
        }
        latestEventTimes.put(taskId, System.currentTimeMillis());
    }

    Optional<IngestionTaskProgressEvent> latest(String taskId) {
        return Optional.ofNullable(latestEvents.get(taskId));
    }

    private boolean replay(String taskId, SseEmitter emitter, long lastEventId) {
        Deque<IngestionTaskProgressEvent> history = eventHistory.get(taskId);
        if (history == null) {
            return true;
        }
        boolean replayed = false;
        for (IngestionTaskProgressEvent event : history) {
            if (event.sequence() > lastEventId) {
                if (!send(taskId, emitter, event)) {
                    return false;
                }
                replayed = true;
            }
        }
        if (!replayed) {
            IngestionTaskProgressEvent latest = latestEvents.get(taskId);
            if (latest != null && latest.sequence() > lastEventId) {
                return send(taskId, emitter, latest);
            }
        }
        return true;
    }

    private boolean send(String taskId, SseEmitter emitter, IngestionTaskProgressEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name("ingestion-progress")
                    .data(objectMapper.writeValueAsString(event)));
            return true;
        } catch (IOException e) {
            log.debug("发送摄入任务 SSE 失败", e);
            removeEmitter(taskId, emitter);
            return false;
        }
    }

    private void removeEmitter(String taskId, SseEmitter emitter) {
        Set<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters == null) {
            return;
        }
        taskEmitters.remove(emitter);
        if (taskEmitters.isEmpty()) {
            emitters.remove(taskId, taskEmitters);
        }
    }

    private Object lockFor(String taskId) {
        return taskLocks.computeIfAbsent(taskId, ignored -> new Object());
    }

    private void purgeExpiredTerminalEvents() {
        long cutoff = System.currentTimeMillis() - TERMINAL_EVENT_RETENTION_MILLIS;
        latestEvents.forEach((taskId, event) -> {
            Long eventTime = latestEventTimes.get(taskId);
            if (!TERMINAL_STATUSES.contains(event.status())
                    || eventTime == null
                    || eventTime >= cutoff
                    || emitters.containsKey(taskId)) {
                return;
            }
            if (latestEvents.remove(taskId, event)) {
                eventHistory.remove(taskId);
                sequenceCounters.remove(taskId);
                latestEventTimes.remove(taskId);
                taskLocks.remove(taskId);
            }
        });
    }

    private IngestionTaskProgressEvent nextEvent(IngestionTask task) {
        return IngestionTaskProgressEvent.builder()
                .taskId(task.getId())
                .sequence(sequenceCounters.computeIfAbsent(task.getId(), ignored -> new AtomicLong()).incrementAndGet())
                .kbId(task.getKbId())
                .documentId(task.getDocumentId())
                .status(task.getStatus())
                .attemptCount(task.getAttemptCount())
                .maxAttempts(task.getMaxAttempts())
                .errorSummary(task.getErrorSummary())
                .build();
    }
}
