package com.livevote.votes.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned domain event emitted after an anonymous vote is committed.
 */
public record VoteCastEvent(
        int eventVersion,
        UUID voteId,
        UUID pollId,
        UUID optionId,
        Instant castAt
) {
}
