package com.livevote.votes.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and parses HS256 JWT tokens for presenter accounts.
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey secretKey;
    private final long expirationSeconds;

    public JwtService(JwtProperties jwtProperties) {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes long");
        }
        if (jwtProperties.getExpirationSeconds() <= 0) {
            throw new IllegalArgumentException("JWT expirationSeconds must be positive");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = jwtProperties.getExpirationSeconds();
    }

    public String generateToken(UUID userId, String email) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expirationSeconds);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public UUID extractUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return UUID.fromString(claims.getSubject());
    }
}
