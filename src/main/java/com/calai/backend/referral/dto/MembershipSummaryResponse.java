package com.calai.backend.referral.dto;

import java.time.Instant;

public record MembershipSummaryResponse(
        String premiumStatus,
        Instant currentPremiumUntil,
        String latestRewardSource,
        Instant latestOldPremiumUntil,
        Instant latestNewPremiumUntil,
        Instant latestGrantedAtUtc
) {}
