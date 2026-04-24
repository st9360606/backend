package com.calai.backend.referral.dto;

import java.time.Instant;

public record RewardHistoryItemDto(
        Long id,
        String sourceType,
        Long sourceRefId,
        Integer daysAdded,
        Instant oldPremiumUntil,
        Instant newPremiumUntil,
        Instant grantedAtUtc
) {}
