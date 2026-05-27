package com.kama.jchatmind.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RedisKey {

    private RedisKey() {
    }

    private static final String PREFIX = "email:";

    public static String verificationCode(String type, String email) {
        return PREFIX + type + ":verification_code:" + email;
    }

    public static String emailLimit(String type, String email) {
        return PREFIX + type + ":verification_code:limit:" + email;
    }

    public static String ipLimit(String type, String ip) {
        return PREFIX + type + ":verification_code:limit:ip:" + md5(ip);
    }

    public static String emailIpRateLimit(String type, String email, String ip) {
        return PREFIX + type + ":verification_code:rate_limit:" + md5(email + ip);
    }

    public static String errorCount(String type, String email) {
        return PREFIX + type + ":verification_code:error:" + email;
    }

    public static String idempotent(String taskId) {
        return PREFIX + "idempotent:" + taskId;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
