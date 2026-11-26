package com.livevote.votes.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for presenter login.
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
