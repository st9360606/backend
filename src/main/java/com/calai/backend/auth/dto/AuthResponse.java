package com.calai.backend.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,             // 預設 "Bearer"
        Long   accessExpiresInSec,    // 如 3600
        Long   refreshExpiresInSec,   // 如 2592000
        Long   serverTimeEpochSec     // 伺服器時間（可用於校時）
) {
    public AuthResponse(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, "Bearer", null, null, null);
    }
}
