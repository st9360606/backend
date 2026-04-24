package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralOutcomeType;

import java.time.Instant;

public record ReferralOutcomeEvent(
        Long claimId,
        Long inviterUserId,
        ReferralOutcomeType outcomeType,
        String rejectReason,
        Instant oldPremiumUntil,
        Instant newPremiumUntil,
        Instant rewardedAtUtc
) {}
