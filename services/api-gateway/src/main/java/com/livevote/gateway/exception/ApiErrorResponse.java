package com.livevote.gateway.exception;

import java.time.Instant;

/**
 * Uniform JSON error payload returned by the Gateway on 401, 429, or 503 fallback responses.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
