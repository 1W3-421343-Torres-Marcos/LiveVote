package com.livevote.votes.exception;

import java.time.Instant;

/**
 * Standard JSON error payload returned by votes-service.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
