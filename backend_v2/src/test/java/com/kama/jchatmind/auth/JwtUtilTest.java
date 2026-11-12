package com.kama.jchatmind.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    // HS512 要求密钥至少 64 字节（512 位），不足会抛 WeakKeyException
    private static final String SECRET = "jchatmind-unit-test-signing-key-0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "jchatmind-unit-test-signing-key-fedcba9876543210fedcba9876543210";

    @Test
    void shouldRoundTripUserIdThroughGeneratedToken() {
        JwtUtil jwtUtil = jwtUtil(3600L);

        String token = jwtUtil.generateToken(42L);

        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(42L);
        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    @Test
    void shouldTreatExpiredTokenAsInvalidInsteadOfThrowing() {
        JwtUtil jwtUtil = jwtUtil(-3600L);

        String token = jwtUtil.generateToken(42L);

        assertThat(jwtUtil.validateToken(token)).isFalse();
        assertThat(jwtUtil.getUserIdFromToken(token)).isNull();
    }

    @Test
    void shouldRejectTokenWithTamperedSignature() {
        JwtUtil jwtUtil = jwtUtil(3600L);
        String token = jwtUtil.generateToken(42L);
        int lastDot = token.lastIndexOf('.');
        String signature = token.substring(lastDot + 1);
        String flippedSignature = (signature.charAt(0) == 'A' ? 'B' : 'A') + signature.substring(1);

        String tampered = token.substring(0, lastDot + 1) + flippedSignature;

        assertThat(jwtUtil.validateToken(tampered)).isFalse();
        assertThat(jwtUtil.getUserIdFromToken(tampered)).isNull();
    }

    @Test
    void shouldRejectTokenSignedWithDifferentSecret() {
        JwtUtil signing = jwtUtil(3600L);
        JwtUtil verifying = jwtUtilWithSecret(OTHER_SECRET, 3600L);

        String token = signing.generateToken(42L);

        assertThat(verifying.validateToken(token)).isFalse();
        assertThat(verifying.getUserIdFromToken(token)).isNull();
    }

    private JwtUtil jwtUtil(long expirationSeconds) {
        return jwtUtilWithSecret(SECRET, expirationSeconds);
    }

    private JwtUtil jwtUtilWithSecret(String secret, long expirationSeconds) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        ReflectionTestUtils.setField(jwtUtil, "expiration", expirationSeconds);
        return jwtUtil;
    }
}
