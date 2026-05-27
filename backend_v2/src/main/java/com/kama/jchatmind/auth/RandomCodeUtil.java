package com.kama.jchatmind.auth;

import java.security.SecureRandom;

public final class RandomCodeUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomCodeUtil() {
    }

    public static String generate() {
        int code = RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }
}
