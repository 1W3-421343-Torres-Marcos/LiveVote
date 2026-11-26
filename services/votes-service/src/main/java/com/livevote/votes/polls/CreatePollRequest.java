package com.livevote.votes.polls;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for creating a poll with 2 to 6 options.
 */
public record CreatePollRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull @Size(min = 2, max = 6) List<@NotBlank @Size(max = 200) String> options
) {
}
