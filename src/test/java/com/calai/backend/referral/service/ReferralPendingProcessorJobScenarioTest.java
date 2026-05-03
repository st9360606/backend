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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralPendingProcessorJobScenarioTest {

    @Mock
    private ReferralClaimRepository claimRepository;

    @Mock
    private ReferralRewardProcessingTxService txService;

    @Mock
    private MembershipRewardService membershipRewardService;

    @Mock
    private ReferralRewardQualificationVerifier qualificationVerifier;

    @Mock
    private ReferralOutcomePublisher outcomePublisher;

    @InjectMocks
    private ReferralPendingProcessorJob job;

    @Test
    void processPendingVerification_shouldRecoverStaleProcessingClaimsBeforeNormalProcessing() {
        ReferralClaimEntity stale = claim(10L);
        stale.setStatus(ReferralClaimStatus.PROCESSING_REWARD.name());
        stale.setUpdatedAtUtc(Instant.parse("2026-05-01T00:00:00Z"));

        when(claimRepository.findStaleProcessingClaims(any(Instant.class))).thenReturn(List.of(stale));
        when(claimRepository.findPendingToProcess(any(Instant.class))).thenReturn(List.of());

        job.processPendingVerification();

        verify(txService).markPendingAgain(10L);
    }

    @Test
    void processPendingVerification_shouldSkipClaimWhenAnotherWorkerAlreadyClaimedIt() throws MembershipRewardService.RewardGrantFinalException, MembershipRewardService.RewardGrantDeferredException {
        ReferralClaimEntity pending = claim(10L);
        when(claimRepository.findStaleProcessingClaims(any(Instant.class))).thenReturn(List.of());
        when(claimRepository.findPendingToProcess(any(Instant.class))).thenReturn(List.of(pending));
        when(txService.claimForProcessing(anyLong(), any(Instant.class))).thenReturn(false);

        job.processPendingVerification();

        verify(txService, never()).loadClaim(anyLong());
        verify(membershipRewardService, never()).grantReferralReward(anyLong(), anyLong());
    }

    @Test
    void processPendingVerification_shouldMarkPendingAgainWhenFinalVerificationIsRetryable() {
        ReferralClaimEntity pending = claim(10L);
        when(claimRepository.findStaleProcessingClaims(any(Instant.class))).thenReturn(List.of());
        when(claimRepository.findPendingToProcess(any(Instant.class))).thenReturn(List.of(pending));
        when(txService.claimForProcessing(anyLong(), any(Instant.class))).thenReturn(true);
        when(txService.loadClaim(10L)).thenReturn(pending);
        when(qualificationVerifier.verifyBeforeReward(any(), any(Instant.class)))
                .thenReturn(ReferralRewardQualificationVerifier.VerificationResult.retryLater("google 429"));

        job.processPendingVerification();

        verify(txService).markPendingAgain(10L);
        verify(outcomePublisher, never()).publish(any());
    }

    @Test
    void processPendingVerification_shouldRejectAndNotifyWhenFinalVerificationRejects() {
        ReferralClaimEntity pending = claim(10L);
        when(claimRepository.findStaleProcessingClaims(any(Instant.class))).thenReturn(List.of());
        when(claimRepository.findPendingToProcess(any(Instant.class))).thenReturn(List.of(pending));
        when(txService.claimForProcessing(anyLong(), any(Instant.class))).thenReturn(true);
        when(txService.loadClaim(10L)).thenReturn(pending);
        when(qualificationVerifier.verifyBeforeReward(any(), any(Instant.class)))
                .thenReturn(ReferralRewardQualificationVerifier.VerificationResult.reject(
                        ReferralRejectReason.PURCHASE_PENDING.name(),
                        "still pending"
                ));

        job.processPendingVerification();

        verify(txService).markRejected(10L, ReferralRejectReason.PURCHASE_PENDING.name());
        ArgumentCaptor<ReferralOutcomeEvent> eventCaptor = ArgumentCaptor.forClass(ReferralOutcomeEvent.class);
        verify(outcomePublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().outcomeType()).isEqualTo(ReferralOutcomeType.REJECTED);
        assertThat(eventCaptor.getValue().rejectReason()).isEqualTo(ReferralRejectReason.PURCHASE_PENDING.name());
    }

    @Test
    void processPendingVerification_shouldMarkSuccessAndNotifyWhenRewardGrantSucceeds()
            throws MembershipRewardService.RewardGrantDeferredException,
            MembershipRewardService.RewardGrantFinalException {
        ReferralClaimEntity pending = claim(10L);
        Instant oldUntil = Instant.parse("2026-05-01T00:00:00Z");
        Instant newUntil = Instant.parse("2026-05-31T00:00:00Z");
        Instant grantedAt = Instant.parse("2026-05-10T00:00:00Z");

        when(claimRepository.findStaleProcessingClaims(any(Instant.class))).thenReturn(List.of());
        when(claimRepository.findPendingToProcess(any(Instant.class))).thenReturn(List.of(pending));
        when(txService.claimForProcessing(anyLong(), any(Instant.class))).thenReturn(true);
        when(txService.loadClaim(10L)).thenReturn(pending);
        when(qualificationVerifier.verifyBeforeReward(any(), any(Instant.class)))
                .thenReturn(ReferralRewardQualificationVerifier.VerificationResult.success());
        when(membershipRewardService.grantReferralReward(100L, 10L))
                .thenReturn(new MembershipRewardService.RewardGrantResult(oldUntil, newUntil, grantedAt));

        job.processPendingVerification();

        verify(txService).markSuccess(10L, grantedAt);
        ArgumentCaptor<ReferralOutcomeEvent> eventCaptor = ArgumentCaptor.forClass(ReferralOutcomeEvent.class);
        verify(outcomePublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().outcomeType()).isEqualTo(ReferralOutcomeType.GRANTED);
        assertThat(eventCaptor.getValue().oldPremiumUntil()).isEqualTo(oldUntil);
        assertThat(eventCaptor.getValue().newPremiumUntil()).isEqualTo(newUntil);
    }

    private ReferralClaimEntity claim(Long id) {
        ReferralClaimEntity claim = new ReferralClaimEntity();
        claim.setId(id);
        claim.setInviterUserId(100L);
        claim.setInviteeUserId(200L);
        claim.setPromoCode("GOOD123");
        claim.setStatus(ReferralClaimStatus.PENDING_VERIFICATION.name());
        claim.setRejectReason(ReferralRejectReason.NONE.name());
        claim.setVerificationDeadlineUtc(Instant.parse("2026-05-09T00:00:00Z"));
        return claim;
    }
}
