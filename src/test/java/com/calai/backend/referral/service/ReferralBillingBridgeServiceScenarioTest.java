package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralClaimStatus;
import com.calai.backend.referral.domain.ReferralOutcomeType;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralBillingBridgeServiceScenarioTest {

    @Mock
    private ReferralClaimRepository claimRepository;

    @Mock
    private ReferralOutcomePublisher outcomePublisher;

    @Mock
    private ReferralRiskService referralRiskService;

    @InjectMocks
    private ReferralBillingBridgeService service;

    @Test
    void pendingPurchase_shouldKeepClaimPendingSubscriptionAndStoreTokenHashWithoutNotification() {
        Instant now = Instant.parse("2026-05-02T00:00:00Z");
        ReferralClaimEntity claim = pendingClaim();
        when(claimRepository.findByInviteeUserId(200L)).thenReturn(Optional.of(claim));

        service.onFirstPaidSubscriptionVerified(
                200L,
                "token-hash-pending",
                now,
                true,
                true,
                false
        );

        assertThat(claim.getStatus()).isEqualTo(ReferralClaimStatus.PENDING_SUBSCRIPTION.name());
        assertThat(claim.getRejectReason()).isEqualTo(ReferralRejectReason.NONE.name());
        assertThat(claim.getPurchaseTokenHash()).isEqualTo("token-hash-pending");
        assertThat(claim.getSubscribedAtUtc()).isEqualTo(now);
        assertThat(claim.getAutoRenewStatus()).isEqualTo("ON");
        assertThat(claim.getVerificationDeadlineUtc()).isNull();

        verify(referralRiskService).assessPaidSubscription(
                claim,
                "token-hash-pending",
                true,
                false,
                now
        );
        verify(outcomePublisher, never()).publish(any());
    }

    @Test
    void firstValidPaidSubscription_shouldEnterSevenDayVerificationWindow() {
        Instant subscribedAt = Instant.parse("2026-05-02T00:00:00Z");
        ReferralClaimEntity claim = pendingClaim();
        when(claimRepository.findByInviteeUserId(200L)).thenReturn(Optional.of(claim));
        when(referralRiskService.assessPaidSubscription(
                eq(claim),
                eq("token-hash-paid"),
                eq(false),
                eq(false),
                eq(subscribedAt)
        )).thenReturn(new ReferralRiskService.RiskResult(0, "ALLOW", "[]"));

        service.onFirstPaidSubscriptionVerified(
                200L,
                "token-hash-paid",
                subscribedAt,
                false,
                false,
                false
        );

        assertThat(claim.getPurchaseTokenHash()).isEqualTo("token-hash-paid");
        assertThat(claim.getStatus()).isEqualTo(ReferralClaimStatus.PENDING_VERIFICATION.name());
        assertThat(claim.getRejectReason()).isEqualTo(ReferralRejectReason.NONE.name());
        assertThat(claim.getQualifiedAtUtc()).isEqualTo(subscribedAt);
        assertThat(claim.getVerificationDeadlineUtc()).isEqualTo(subscribedAt.plusSeconds(7L * 24L * 3600L));
        assertThat(claim.getAutoRenewStatus()).isEqualTo("OFF");
        verify(outcomePublisher, never()).publish(any());
    }

    @Test
    void testPurchase_shouldRejectAndPublishFailureOutcome() {
        Instant now = Instant.parse("2026-05-02T00:00:00Z");
        ReferralClaimEntity claim = pendingClaim();
        when(claimRepository.findByInviteeUserId(200L)).thenReturn(Optional.of(claim));

        service.onFirstPaidSubscriptionVerified(
                200L,
                "token-hash-test",
                now,
                true,
                false,
                true
        );

        assertThat(claim.getStatus()).isEqualTo(ReferralClaimStatus.REJECTED.name());
        assertThat(claim.getRejectReason()).isEqualTo(ReferralRejectReason.TEST_PURCHASE.name());

        ArgumentCaptor<ReferralOutcomeEvent> eventCaptor = ArgumentCaptor.forClass(ReferralOutcomeEvent.class);
        verify(outcomePublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().outcomeType()).isEqualTo(ReferralOutcomeType.REJECTED);
        assertThat(eventCaptor.getValue().rejectReason()).isEqualTo(ReferralRejectReason.TEST_PURCHASE.name());
    }

    @Test
    void riskDenied_shouldRejectAbuseRiskAndPublishFailureOutcome() {
        Instant now = Instant.parse("2026-05-02T00:00:00Z");
        ReferralClaimEntity claim = pendingClaim();
        when(claimRepository.findByInviteeUserId(200L)).thenReturn(Optional.of(claim));
        when(referralRiskService.assessPaidSubscription(
                eq(claim),
                eq("token-hash-risk"),
                eq(false),
                eq(false),
                eq(now)
        )).thenReturn(new ReferralRiskService.RiskResult(100, "DENY", "[\"SELF_REFERRAL\"]"));

        service.onFirstPaidSubscriptionVerified(
                200L,
                "token-hash-risk",
                now,
                true,
                false,
                false
        );

        assertThat(claim.getStatus()).isEqualTo(ReferralClaimStatus.REJECTED.name());
        assertThat(claim.getRejectReason()).isEqualTo(ReferralRejectReason.ABUSE_RISK.name());

        ArgumentCaptor<ReferralOutcomeEvent> eventCaptor = ArgumentCaptor.forClass(ReferralOutcomeEvent.class);
        verify(outcomePublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().outcomeType()).isEqualTo(ReferralOutcomeType.REJECTED);
        assertThat(eventCaptor.getValue().rejectReason()).isEqualTo(ReferralRejectReason.ABUSE_RISK.name());
    }

    @Test
    void pendingPurchaseCanceled_shouldRejectClaimByTokenHashAndPublishFailureOutcome() {
        Instant detectedAt = Instant.parse("2026-05-03T00:00:00Z");
        ReferralClaimEntity claim = pendingClaim();
        claim.setPurchaseTokenHash("token-hash-pending");
        when(claimRepository.findByPurchaseTokenHash("token-hash-pending")).thenReturn(Optional.of(claim));

        service.markRefundedOrRevoked("token-hash-pending", false, detectedAt);

        assertThat(claim.getStatus()).isEqualTo(ReferralClaimStatus.REJECTED.name());
        assertThat(claim.getRejectReason()).isEqualTo(ReferralRejectReason.REFUNDED_OR_REVOKED.name());
        assertThat(claim.getRefundDetectedAtUtc()).isEqualTo(detectedAt);

        ArgumentCaptor<ReferralOutcomeEvent> eventCaptor = ArgumentCaptor.forClass(ReferralOutcomeEvent.class);
        verify(outcomePublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().outcomeType()).isEqualTo(ReferralOutcomeType.REJECTED);
        assertThat(eventCaptor.getValue().rejectReason()).isEqualTo(ReferralRejectReason.REFUNDED_OR_REVOKED.name());
    }

    @Test
    void chargeback_shouldRejectWithChargebackReason() {
        Instant detectedAt = Instant.parse("2026-05-03T00:00:00Z");
        ReferralClaimEntity claim = pendingClaim();
        claim.setPurchaseTokenHash("token-hash-chargeback");
        when(claimRepository.findByPurchaseTokenHash("token-hash-chargeback")).thenReturn(Optional.of(claim));

        service.markRefundedOrRevoked("token-hash-chargeback", true, detectedAt);

        assertThat(claim.getStatus()).isEqualTo(ReferralClaimStatus.REJECTED.name());
        assertThat(claim.getRejectReason()).isEqualTo(ReferralRejectReason.CHARGEBACK.name());
    }

    private ReferralClaimEntity pendingClaim() {
        ReferralClaimEntity claim = new ReferralClaimEntity();
        claim.setId(10L);
        claim.setInviterUserId(100L);
        claim.setInviteeUserId(200L);
        claim.setPromoCode("GOOD123");
        claim.setStatus(ReferralClaimStatus.PENDING_SUBSCRIPTION.name());
        claim.setRejectReason(ReferralRejectReason.NONE.name());
        return claim;
    }
}
