package com.calai.backend.referral.dto;

import java.time.Instant;

public record MembershipSummaryResponse(
        String premiumStatus,
        Instant currentPremiumUntil,
        Instant trialEndsAt,
        Integer trialDaysLeft,
        Boolean trialEligible,
        Boolean paymentIssue,
        String latestRewardSource,
        String latestRewardChannel,
        String latestRewardGrantStatus,
        String latestGoogleDeferStatus,
        Instant latestOldPremiumUntil,
        Instant latestNewPremiumUntil,
        Instant latestGrantedAtUtc
) {}
