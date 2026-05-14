package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralClaimStatus;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralQueryServiceScenarioTest {

    @Mock
    private ReferralCodeService referralCodeService;

    @Mock
    private ReferralClaimRepository claimRepository;

    @InjectMocks
    private ReferralQueryService service;

    @Test
    void getSummary_shouldCountPendingSubscriptionCooldownAndProcessingAsPending() {
        when(referralCodeService.getOrCreateCode(100L)).thenReturn("GOOD123");
        when(claimRepository.countByInviterUserIdAndStatus(100L, ReferralClaimStatus.SUCCESS.name())).thenReturn(1L);
        when(claimRepository.countByInviterUserIdAndStatusIn(100L, List.of(
                ReferralClaimStatus.PENDING_SUBSCRIPTION.name(),
                ReferralClaimStatus.PENDING_COOLDOWN.name(),
                ReferralClaimStatus.PENDING_VERIFICATION.name(),
                ReferralClaimStatus.PROCESSING_REWARD.name()
        ))).thenReturn(3L);
        when(claimRepository.countByInviterUserIdAndStatusIn(100L, List.of(
                ReferralClaimStatus.REJECTED.name(),
                ReferralClaimStatus.EXPIRED.name()
        ))).thenReturn(2L);
        when(claimRepository.findTop20ByInviterUserIdOrderByCreatedAtUtcDesc(100L)).thenReturn(List.of(claim()));

        var summary = service.getSummary(100L);

        assertThat(summary.promoCode()).isEqualTo("GOOD123");
        assertThat(summary.successCount()).isEqualTo(1L);
        assertThat(summary.pendingVerificationCount()).isEqualTo(3L);
        assertThat(summary.rejectedCount()).isEqualTo(2L);
        assertThat(summary.recentClaims()).hasSize(1);
        assertThat(summary.recentClaims().get(0).verificationDeadlineUtc())
                .isEqualTo(Instant.parse("2026-05-09T00:00:00Z"));
    }

    private ReferralClaimEntity claim() {
        ReferralClaimEntity claim = new ReferralClaimEntity();
        claim.setId(10L);
        claim.setInviterUserId(100L);
        claim.setInviteeUserId(200L);
        claim.setPromoCode("GOOD123");
        claim.setStatus(ReferralClaimStatus.PENDING_COOLDOWN.name());
        claim.setCooldownUntilUtc(Instant.parse("2026-05-09T00:00:00Z"));
        return claim;
    }
}
