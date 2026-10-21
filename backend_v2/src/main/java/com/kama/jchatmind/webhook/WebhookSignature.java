package com.kama.jchatmind.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

final class WebhookSignature {

    private static final byte SIGNATURE_SCHEMA_VERSION = 1;

    private WebhookSignature() {
    }

    static String sign(String sourceId, String eventId, Instant timestamp, String payload, byte[] signingKey) {
        byte[] source = sourceId.getBytes(StandardCharsets.UTF_8);
        byte[] id = eventId.getBytes(StandardCharsets.UTF_8);
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(
                        Byte.BYTES + Integer.BYTES + source.length + Integer.BYTES + id.length
                                + Long.BYTES + Integer.BYTES + body.length
                )
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(SIGNATURE_SCHEMA_VERSION);
        putLengthPrefixedText(buffer, source);
        putLengthPrefixedText(buffer, id);
        buffer.putLong(timestamp.getEpochSecond());
        putLengthPrefixedText(buffer, body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(buffer.array()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法初始化 Webhook 签名", exception);
        }
    }

    private static void putLengthPrefixedText(ByteBuffer buffer, byte[] value) {
        buffer.putInt(value.length);
        buffer.put(value);
    }
}
