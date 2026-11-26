package com.livevote.results.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Incoming VoteCast event contract (v1).
 */
public record VoteCastEvent(
        Integer eventVersion,
        UUID voteId,
        UUID pollId,
        UUID optionId,
        Instant castAt
) {
}
