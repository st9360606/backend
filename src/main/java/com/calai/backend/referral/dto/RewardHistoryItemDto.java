package com.calai.backend.referral.dto;

import java.time.Instant;

public record RewardHistoryItemDto(
        Long id,
        String sourceType,
        Long sourceRefId,
        String grantStatus,
        String rewardChannel,
        String googleDeferStatus,
        String errorCode,
        Integer daysAdded,
        Instant oldPremiumUntil,
        Instant newPremiumUntil,
        Instant grantedAtUtc
) {}
