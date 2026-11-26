package com.livevote.votes.dto;

import java.util.UUID;

/**
 * Authentication response carrying a short-lived HS256 JWT.
 */
public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email
) {
}
