package com.calai.backend.referral.dto;

import java.time.Instant;

public record ReferralClaimItemDto(
        Long claimId,
        String displayName,
        String status,
        Instant verificationDeadlineUtc,
        Instant rewardedAtUtc,
        String rejectReason
) {}
