package com.caloshape.backend.auth.dto;

public record RefreshRequest(String refreshToken, String deviceId) {}
