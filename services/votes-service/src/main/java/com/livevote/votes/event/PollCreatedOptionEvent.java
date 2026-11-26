package com.livevote.votes.event;

import java.util.UUID;

/**
 * Option payload embedded in PollCreatedEvent.
 */
public record PollCreatedOptionEvent(
        UUID optionId,
        String text
) {
}
