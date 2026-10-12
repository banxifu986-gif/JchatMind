package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class RedisChatSseEventStore {

    public static final int MAX_HISTORY_EVENTS = 64;
    public static final int HISTORY_RETENTION_SECONDS = 1800;
    public static final String EVENT_CHANNEL_PATTERN = "jchatmind:chat-sse:*:events";

    private static final DefaultRedisScript<String> APPEND_EVENT_SCRIPT = new DefaultRedisScript<>("""
            local sequence = redis.call('INCR', KEYS[1])
            local event = cjson.encode({
                sessionId = ARGV[1],
                sequence = sequence,
                serializedMessage = ARGV[2]
            })
            redis.call('RPUSH', KEYS[2], event)
            redis.call('LTRIM', KEYS[2], -tonumber(ARGV[3]), -1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))
            redis.call('PUBLISH', KEYS[3], event)
            return event
            """, String.class);
    private static final DefaultRedisScript<String> READ_SNAPSHOT_SCRIPT = new DefaultRedisScript<>("""
            local sequence = tonumber(redis.call('GET', KEYS[1]) or '0')
            local events = redis.call('LRANGE', KEYS[2], 0, -1)
            return cjson.encode({latestSequence = sequence, events = events})
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatSseEventStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Event append(String sessionId, String serializedMessage) {
        requireSessionId(sessionId);
        if (serializedMessage == null) {
            throw new IllegalArgumentException("Serialized chat SSE message is required");
        }
        Keys keys = keys(sessionId);
        String eventJson = redisTemplate.execute(
                APPEND_EVENT_SCRIPT,
                List.of(keys.sequenceKey(), keys.historyKey(), keys.channel()),
                sessionId,
                serializedMessage,
                Integer.toString(MAX_HISTORY_EVENTS),
                Integer.toString(HISTORY_RETENTION_SECONDS)
        );
        if (!StringUtils.hasText(eventJson)) {
            throw new IllegalStateException("Redis did not return the appended chat SSE event");
        }
        return parseEvent(eventJson);
    }

    public List<Event> readAfter(String sessionId, long lastEventId) {
        return readSnapshot(sessionId).events().stream()
                .filter(event -> event.sequence() > lastEventId)
                .toList();
    }

    public Snapshot readSnapshot(String sessionId) {
        requireSessionId(sessionId);
        Keys keys = keys(sessionId);
        String snapshotJson = redisTemplate.execute(
                READ_SNAPSHOT_SCRIPT,
                List.of(keys.sequenceKey(), keys.historyKey())
        );
        if (!StringUtils.hasText(snapshotJson)) {
            return new Snapshot(0L, List.of());
        }
        try {
            JsonNode snapshot = objectMapper.readTree(snapshotJson);
            JsonNode storedEvents = snapshot.path("events");
            if (!storedEvents.isArray()) {
                throw new IllegalStateException("Stored chat SSE snapshot has no event list");
            }
            List<Event> events = new ArrayList<>(storedEvents.size());
            for (JsonNode storedEvent : storedEvents) {
                events.add(parseEvent(storedEvent.asText()));
            }
            return new Snapshot(snapshot.path("latestSequence").asLong(), List.copyOf(events));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored chat SSE snapshot is invalid", exception);
        }
    }

    public long latestSequence(String sessionId) {
        requireSessionId(sessionId);
        String value = redisTemplate.opsForValue().get(keys(sessionId).sequenceKey());
        return StringUtils.hasText(value) ? Long.parseLong(value) : 0L;
    }

    void delete(String sessionId) {
        requireSessionId(sessionId);
        Keys keys = keys(sessionId);
        redisTemplate.delete(List.of(keys.sequenceKey(), keys.historyKey()));
    }

    private Event parseEvent(String eventJson) {
        try {
            return objectMapper.readValue(eventJson, Event.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored chat SSE event is invalid", exception);
        }
    }

    private Keys keys(String sessionId) {
        String encodedSessionId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        String prefix = "jchatmind:chat-sse:{" + encodedSessionId + "}";
        return new Keys(prefix + ":sequence", prefix + ":history", prefix + ":events");
    }

    private void requireSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("Chat session id is required");
        }
    }

    public record Event(String sessionId, long sequence, String serializedMessage) {
    }

    public record Snapshot(long latestSequence, List<Event> events) {
    }

    private record Keys(String sequenceKey, String historyKey, String channel) {
    }
}
