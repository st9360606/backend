package com.calai.backend.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken
) {}