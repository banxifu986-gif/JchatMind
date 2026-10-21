package com.kama.jchatmind.webhook;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class RedisWebhookEventIdRegistry implements WebhookEventIdRegistry {

    private static final String KEY_PREFIX = "jchatmind:webhook:{";
    private static final String KEY_SUFFIX = "}:event-id";

    private final StringRedisTemplate redisTemplate;

    public RedisWebhookEventIdRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean reserve(String sourceId, String eventId, Duration retention) {
        if (sourceId == null || eventId == null || retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Webhook event reservation requires source, id, and positive retention");
        }
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                redisKey(sourceId, eventId),
                "reserved",
                retention
        ));
    }

    private String redisKey(String sourceId, String eventId) {
        return KEY_PREFIX + eventIdentityHash(sourceId, eventId) + KEY_SUFFIX;
    }

    static String eventIdentityHash(String sourceId, String eventId) {
        byte[] source = sourceId.getBytes(StandardCharsets.UTF_8);
        byte[] event = eventId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer keyMaterial = ByteBuffer.allocate(Integer.BYTES + source.length + Integer.BYTES + event.length)
                .order(ByteOrder.BIG_ENDIAN);
        keyMaterial.putInt(source.length);
        keyMaterial.put(source);
        keyMaterial.putInt(event.length);
        keyMaterial.put(event);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyMaterial.array()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to create webhook event key", exception);
        }
    }
}
