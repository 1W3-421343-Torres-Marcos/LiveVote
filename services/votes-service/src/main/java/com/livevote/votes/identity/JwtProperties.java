package com.livevote.votes.identity;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for HS256 JWT issuance and validation.
 */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private static final int MIN_SECRET_BYTES = 32;

    private String secret;
    private long expirationSeconds = 7200;

    @PostConstruct
    void validate() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes long");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalStateException("app.jwt.expiration-seconds must be positive");
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public void setExpirationSeconds(long expirationSeconds) {
        this.expirationSeconds = expirationSeconds;
    }
}
