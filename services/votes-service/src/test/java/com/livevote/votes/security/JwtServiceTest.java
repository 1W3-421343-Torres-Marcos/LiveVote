package com.livevote.votes.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.livevote.votes.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String VALID_SECRET = "test-jwt-secret-with-at-least-32-bytes-length";

    @Test
    void shouldGenerateAndExtractUserId() {
        JwtProperties props = new JwtProperties();
        props.setSecret(VALID_SECRET);
        props.setExpirationSeconds(7200);
        JwtService jwtService = new JwtService(props);

        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "presenter@livevote.test");

        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void shouldRejectTokenSignedWithDifferentSecret() {
        JwtProperties issuerProps = new JwtProperties();
        issuerProps.setSecret(VALID_SECRET);
        issuerProps.setExpirationSeconds(7200);
        JwtService issuer = new JwtService(issuerProps);

        JwtProperties verifierProps = new JwtProperties();
        verifierProps.setSecret("another-completely-different-secret-32-bytes");
        verifierProps.setExpirationSeconds(7200);
        JwtService verifier = new JwtService(verifierProps);

        String token = issuer.generateToken(UUID.randomUUID(), "presenter@livevote.test");

        assertThatThrownBy(() -> verifier.extractUserId(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectSecretShorterThan32Bytes() {
        JwtProperties props = new JwtProperties();
        props.setSecret("too-short-secret");
        props.setExpirationSeconds(7200);

        assertThatThrownBy(() -> new JwtService(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
