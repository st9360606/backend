package com.caloshape.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record StartRequest(
        @NotBlank
        @Email
        String email
) {
}
