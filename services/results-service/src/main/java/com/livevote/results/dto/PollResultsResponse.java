package com.livevote.results.dto;

import com.livevote.results.entity.PollProjectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public read projection of a poll and its option vote counts.
 */
public record PollResultsResponse(
        UUID pollId,
        String title,
        PollProjectionStatus status,
        long totalVotes,
        List<OptionResultResponse> options,
        Instant createdAt,
        Instant closedAt
) {
}
