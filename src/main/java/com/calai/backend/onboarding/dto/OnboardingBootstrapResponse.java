package com.calai.backend.onboarding.dto;

public record OnboardingBootstrapResponse(
        String premiumStatus,
        Boolean paymentIssue,
        Boolean paymentRecoveryRequired,
        Boolean hasPaidSubscriptionHistory,
        Boolean trialActive,
        Boolean trialEligible,
        Boolean referralClaimEligible,
        Boolean hasReferralClaim,
        String referralClaimStatus,
        String referralClaimIneligibleReason,
        String nextRecommendedRoute
) {}
