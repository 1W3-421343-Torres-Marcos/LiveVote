package com.livevote.results.event;

import java.util.UUID;

/**
 * Option payload inside incoming PollCreatedEvent JSON.
 */
public record PollCreatedOptionEvent(
        UUID optionId,
        String text
) {
}
