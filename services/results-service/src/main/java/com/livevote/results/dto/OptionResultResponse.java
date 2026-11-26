package com.livevote.results.dto;

import java.util.UUID;

/**
 * Aggregated vote count for a single poll option.
 */
public record OptionResultResponse(
        UUID optionId,
        String text,
        int displayOrder,
        long votes
) {
}
