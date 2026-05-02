package com.calai.backend.referral.domain;

public enum ReferralClaimStatus {
    PENDING_SUBSCRIPTION,
    PENDING_VERIFICATION,

    /**
     * Referral v1.3 stable commercial:
     * A worker has claimed this referral for reward processing.
     *
     * Purpose:
     * - prevent multiple backend instances from calling Google Play defer for the same claim
     * - avoid accidentally extending Google Play subscription twice
     */
    PROCESSING_REWARD,

    SUCCESS,
    REJECTED
}
