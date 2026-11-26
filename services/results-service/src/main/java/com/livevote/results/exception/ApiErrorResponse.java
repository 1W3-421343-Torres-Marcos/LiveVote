package com.livevote.results.exception;

import java.time.Instant;

/**
 * Standard JSON error payload returned by results-service.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
