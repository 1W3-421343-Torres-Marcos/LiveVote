package com.livevote.results.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Incoming PollCreated event contract (v1).
 */
public record PollCreatedEvent(
        Integer eventVersion,
        UUID pollId,
        String title,
        List<PollCreatedOptionEvent> options,
        Instant createdAt
) {
}
