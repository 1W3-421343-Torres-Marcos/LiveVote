package com.livevote.votes.polls;

import java.util.UUID;

/**
 * Option representation returned inside poll responses.
 */
public record PollOptionResponse(
        UUID id,
        String text,
        int displayOrder
) {
}
