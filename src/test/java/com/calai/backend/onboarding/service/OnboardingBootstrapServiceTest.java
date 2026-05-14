package com.calai.backend.onboarding.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.dto.MembershipSummaryResponse;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import com.calai.backend.referral.service.MembershipSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingBootstrapServiceTest {

    @Mock MembershipSummaryService membershipSummaryService;
    @Mock UserEntitlementRepository entitlementRepository;
    @Mock ReferralClaimRepository referralClaimRepository;

    @InjectMocks OnboardingBootstrapService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "referralEnabled", true);
    }

    @Test
    void bootstrap_freeNewEligibleUser_shouldRouteToReferralCode() {
        when(membershipSummaryService.getMembershipSummary(10L)).thenReturn(summary("FREE", false, true));
        when(entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(10L)).thenReturn(false);
        when(referralClaimRepository.findByInviteeUserId(10L)).thenReturn(Optional.empty());

        var result = service.bootstrap(10L);

        assertThat(result.referralClaimEligible()).isTrue();
        assertThat(result.nextRecommendedRoute()).isEqualTo(OnboardingBootstrapService.ROUTE_ONBOARD_REFERRAL_CODE);
        assertThat(result.referralClaimIneligibleReason()).isNull();
    }

    @Test
    void bootstrap_trialActive_shouldRouteHomeAndNotShowReferralCode() {
        when(membershipSummaryService.getMembershipSummary(10L)).thenReturn(summary("TRIAL", false, false));
        when(entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(10L)).thenReturn(false);
        when(referralClaimRepository.findByInviteeUserId(10L)).thenReturn(Optional.empty());

        var result = service.bootstrap(10L);

        assertThat(result.trialActive()).isTrue();
        assertThat(result.referralClaimEligible()).isFalse();
        assertThat(result.referralClaimIneligibleReason()).isEqualTo("TRIAL_ACTIVE");
        assertThat(result.nextRecommendedRoute()).isEqualTo(OnboardingBootstrapService.ROUTE_HOME);
    }

    @Test
    void bootstrap_premiumWithPaymentIssue_shouldRouteHomeAndNotShowReferralCode() {
        when(membershipSummaryService.getMembershipSummary(10L)).thenReturn(summary("PREMIUM", true, false));
        when(entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(10L)).thenReturn(true);
        when(referralClaimRepository.findByInviteeUserId(10L)).thenReturn(Optional.empty());

        var result = service.bootstrap(10L);

        assertThat(result.paymentIssue()).isTrue();
        assertThat(result.referralClaimEligible()).isFalse();
        assertThat(result.referralClaimIneligibleReason()).isEqualTo("PAYMENT_ISSUE");
        assertThat(result.nextRecommendedRoute()).isEqualTo(OnboardingBootstrapService.ROUTE_HOME);
    }

    @Test
    void bootstrap_freeWithPaidHistory_shouldRouteSubscriptionRecoveryAndNotShowReferralCode() {
        when(membershipSummaryService.getMembershipSummary(10L)).thenReturn(summary("FREE", false, false));
        when(entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(10L)).thenReturn(true);
        when(referralClaimRepository.findByInviteeUserId(10L)).thenReturn(Optional.empty());

        var result = service.bootstrap(10L);

        assertThat(result.paymentRecoveryRequired()).isTrue();
        assertThat(result.hasPaidSubscriptionHistory()).isTrue();
        assertThat(result.referralClaimEligible()).isFalse();
        assertThat(result.referralClaimIneligibleReason()).isEqualTo("PAYMENT_RECOVERY_REQUIRED");
        assertThat(result.nextRecommendedRoute()).isEqualTo(OnboardingBootstrapService.ROUTE_SUBSCRIPTION);
    }

    @Test
    void bootstrap_alreadyClaimed_shouldNotShowReferralCodeAgain() {
        ReferralClaimEntity claim = new ReferralClaimEntity();
        claim.setStatus("PENDING_SUBSCRIPTION");

        when(membershipSummaryService.getMembershipSummary(10L)).thenReturn(summary("FREE", false, true));
        when(entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(10L)).thenReturn(false);
        when(referralClaimRepository.findByInviteeUserId(10L)).thenReturn(Optional.of(claim));

        var result = service.bootstrap(10L);

        assertThat(result.hasReferralClaim()).isTrue();
        assertThat(result.referralClaimEligible()).isFalse();
        assertThat(result.referralClaimStatus()).isEqualTo("PENDING_SUBSCRIPTION");
        assertThat(result.referralClaimIneligibleReason()).isEqualTo("ALREADY_CLAIMED");
        assertThat(result.nextRecommendedRoute()).isEqualTo(OnboardingBootstrapService.ROUTE_ONBOARD_SUBSCRIPTION);
    }

    private MembershipSummaryResponse summary(String status, boolean paymentIssue, boolean trialEligible) {
        return new MembershipSummaryResponse(
                status,
                null,
                null,
                null,
                trialEligible,
                paymentIssue,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
