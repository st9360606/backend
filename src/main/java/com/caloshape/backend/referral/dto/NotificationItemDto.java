package com.caloshape.backend.referral.dto;

import java.time.Instant;

public record NotificationItemDto(
        Long id,
        String type,
        String title,
        String message,
        String deepLink,
        Instant createdAtUtc,
        boolean read
) {}
