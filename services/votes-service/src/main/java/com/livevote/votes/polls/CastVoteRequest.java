package com.livevote.votes.polls;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for submitting an anonymous vote identified by deviceId.
 */
public record CastVoteRequest(
        @NotNull UUID optionId,
        @NotBlank @Size(max = 100) String deviceId
) {
}
