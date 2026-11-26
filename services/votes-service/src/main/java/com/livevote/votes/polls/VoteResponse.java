package com.livevote.votes.polls;

import java.time.Instant;
import java.util.UUID;

/**
 * Confirmation payload returned after persisting a vote.
 */
public record VoteResponse(
        UUID voteId,
        UUID pollId,
        UUID optionId,
        Instant castAt
) {
}
