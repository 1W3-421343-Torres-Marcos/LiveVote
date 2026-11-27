package com.livevote.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.livevote.gateway.config.JwtProperties;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtValidatorTest {

    private static final String SECRET = "test-jwt-secret-with-at-least-32-bytes-length";

    @Test
    void shouldExtractUserIdFromValidHs256Token() {
        JwtValidator validator = createValidator(SECRET);
        UUID userId = UUID.randomUUID();
        String token = signToken(SECRET, userId, Instant.now().plusSeconds(3600));

        assertThat(validator.validateAndExtractUserId(token)).isEqualTo(userId);
    }

    @Test
    void shouldRejectExpiredAndTamperedTokens() {
        JwtValidator validator = createValidator(SECRET);
        UUID userId = UUID.randomUUID();

        String expired = signToken(SECRET, userId, Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> validator.validateAndExtractUserId(expired))
                .isInstanceOf(ExpiredJwtException.class);

        String wrongSecretToken = signToken("another-completely-different-secret-32-bytes", userId,
                Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> validator.validateAndExtractUserId(wrongSecretToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> createValidator("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    private JwtValidator createValidator(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        return new JwtValidator(properties);
    }

    private String signToken(String secret, UUID userId, Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
