package com.livevote.results.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned domain event consumed when a poll owner closes a poll.
 */
public record PollClosedEvent(
        int eventVersion,
        UUID pollId,
        Instant closedAt
) {
}
