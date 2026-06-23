package com.caloshape.backend.referral.domain;

public enum ReferralClaimStatus {
    PENDING_SUBSCRIPTION,

    /**
     * Commercial name for the 7-day refund/revoke/fraud verification window
     * after the invitee completes the first valid paid subscription.
     */
    PENDING_COOLDOWN,

    /**
     * Legacy status kept for backward compatibility with old rows.
     * New code should write PENDING_COOLDOWN.
     */
    PENDING_VERIFICATION,

    /**
     * Referral was claimed but the invitee did not complete a valid paid
     * subscription before the attribution window expired.
     */
    EXPIRED,

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
