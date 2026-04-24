package com.calai.backend.referral.dto;

import java.util.List;

public record ReferralSummaryResponse(
        String promoCode,
        long successCount,
        long pendingVerificationCount,
        long rejectedCount,
        List<ReferralClaimItemDto> recentClaims
) {}
