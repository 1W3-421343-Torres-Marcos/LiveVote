package com.livevote.votes.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned domain event emitted after a poll is persisted (excludes owner identity).
 */
public record PollCreatedEvent(
        int eventVersion,
        UUID pollId,
        String title,
        List<PollCreatedOptionEvent> options,
        Instant createdAt
) {
}
