package com.calai.backend.onboarding.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.onboarding.dto.OnboardingBootstrapResponse;
import com.calai.backend.referral.domain.PremiumStatus;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import com.calai.backend.referral.service.MembershipSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class OnboardingBootstrapService {

    public static final String ROUTE_HOME = "HOME";
    public static final String ROUTE_ONBOARD_REFERRAL_CODE = "ONBOARD_REFERRAL_CODE";
    public static final String ROUTE_ONBOARD_SUBSCRIPTION = "ONBOARD_SUBSCRIPTION";
    public static final String ROUTE_SUBSCRIPTION = "SUBSCRIPTION";

    private final MembershipSummaryService membershipSummaryService;
    private final UserEntitlementRepository entitlementRepository;
    private final ReferralClaimRepository referralClaimRepository;

    @Value("${app.referral.enabled:true}")
    private boolean referralEnabled;

    @Transactional(readOnly = true)
    public OnboardingBootstrapResponse bootstrap(Long userId) {
        var membership = membershipSummaryService.getMembershipSummary(userId);

        String premiumStatus = membership.premiumStatus() == null
                ? PremiumStatus.FREE.name()
                : membership.premiumStatus();
        boolean paymentIssue = Boolean.TRUE.equals(membership.paymentIssue());
        boolean trialEligible = Boolean.TRUE.equals(membership.trialEligible());
        boolean trialActive = PremiumStatus.TRIAL.name().equals(premiumStatus);
        boolean premiumActive = PremiumStatus.PREMIUM.name().equals(premiumStatus);
        boolean hasPaidSubscriptionHistory = entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(userId);

        ReferralClaimEntity existingClaim = referralClaimRepository.findByInviteeUserId(userId).orElse(null);
        boolean hasReferralClaim = existingClaim != null;
        String referralClaimStatus = hasReferralClaim ? existingClaim.getStatus() : null;

        boolean paymentRecoveryRequired = PremiumStatus.FREE.name().equals(premiumStatus) && hasPaidSubscriptionHistory;

        String ineligibleReason = referralIneligibleReason(
                premiumStatus,
                paymentIssue,
                paymentRecoveryRequired,
                hasPaidSubscriptionHistory,
                hasReferralClaim
        );
        boolean referralClaimEligible = ineligibleReason == null;

        String nextRoute = nextRecommendedRoute(
                premiumStatus,
                paymentIssue,
                paymentRecoveryRequired,
                hasPaidSubscriptionHistory,
                referralClaimEligible
        );

        return new OnboardingBootstrapResponse(
                premiumStatus,
                paymentIssue,
                paymentRecoveryRequired,
                hasPaidSubscriptionHistory,
                trialActive,
                trialEligible,
                referralClaimEligible,
                hasReferralClaim,
                referralClaimStatus,
                ineligibleReason,
                nextRoute
        );
    }

    private String referralIneligibleReason(
            String premiumStatus,
            boolean paymentIssue,
            boolean paymentRecoveryRequired,
            boolean hasPaidSubscriptionHistory,
            boolean hasReferralClaim
    ) {
        if (!referralEnabled) return "REFERRAL_DISABLED";
        if (PremiumStatus.PREMIUM.name().equals(premiumStatus)) {
            return paymentIssue ? "PAYMENT_ISSUE" : "PREMIUM_ACTIVE";
        }
        if (PremiumStatus.TRIAL.name().equals(premiumStatus)) return "TRIAL_ACTIVE";
        if (paymentRecoveryRequired) return "PAYMENT_RECOVERY_REQUIRED";
        if (hasPaidSubscriptionHistory) return "HAS_PAID_HISTORY";
        if (hasReferralClaim) return "ALREADY_CLAIMED";
        return null;
    }

    private String nextRecommendedRoute(
            String premiumStatus,
            boolean paymentIssue,
            boolean paymentRecoveryRequired,
            boolean hasPaidSubscriptionHistory,
            boolean referralClaimEligible
    ) {
        if (PremiumStatus.PREMIUM.name().equals(premiumStatus)) {
            return ROUTE_HOME;
        }
        if (PremiumStatus.TRIAL.name().equals(premiumStatus)) {
            return ROUTE_HOME;
        }
        if (paymentIssue) {
            return ROUTE_HOME;
        }
        if (paymentRecoveryRequired || hasPaidSubscriptionHistory) {
            return ROUTE_SUBSCRIPTION;
        }
        if (referralClaimEligible) {
            return ROUTE_ONBOARD_REFERRAL_CODE;
        }
        return ROUTE_ONBOARD_SUBSCRIPTION;
    }
}
