package com.livevote.votes.dto;

import com.livevote.votes.entity.PollStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Poll details returned to presenters and public voting screens.
 */
public record PollResponse(
        UUID id,
        String title,
        PollStatus status,
        List<PollOptionResponse> options,
        Instant createdAt,
        Instant closedAt
) {
}
