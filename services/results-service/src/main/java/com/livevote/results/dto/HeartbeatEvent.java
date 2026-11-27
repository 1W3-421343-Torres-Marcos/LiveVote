package com.livevote.results.dto;

import java.time.Instant;

/**
 * Periodic keep-alive SSE payload emitted to prevent idle proxy and browser timeouts.
 */
public record HeartbeatEvent(Instant timestamp) {
}
