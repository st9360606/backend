package com.calai.backend.auth.dto;

public record RefreshRequest(String refreshToken, String deviceId) {}
