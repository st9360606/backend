package com.caloshape.backend.referral.dto;

public record ClaimReferralResponse(
        boolean applied,
        boolean alreadyApplied,
        String claimStatus
) {}
