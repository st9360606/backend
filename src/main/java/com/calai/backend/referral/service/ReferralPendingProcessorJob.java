package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralClaimStatus;
import com.calai.backend.referral.domain.ReferralOutcomeType;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Slf4j
@Component
public class ReferralPendingProcessorJob {
    private final ReferralClaimRepository claimRepository;
    private final MembershipRewardService membershipRewardService;
    private final ReferralOutcomePublisher outcomePublisher;

    @Transactional
    @Scheduled(fixedDelayString = "${referral.process-pending.fixed-delay:PT10M}")
    public void processPendingVerification() {
        Instant now = Instant.now();
        var claims = claimRepository.findPendingToProcess(now);
        for (var claim : claims) {
            if (claim.getRefundDetectedAtUtc() != null) {
                claim.setStatus(ReferralClaimStatus.REJECTED.name());
                if (claim.getRejectReason() == null || "NONE".equals(claim.getRejectReason())) {
                    claim.setRejectReason(ReferralRejectReason.REFUNDED_OR_REVOKED.name());
                }
                outcomePublisher.publish(new ReferralOutcomeEvent(
                        claim.getId(),
                        claim.getInviterUserId(),
                        ReferralOutcomeType.REJECTED,
                        claim.getRejectReason(),
                        null,
                        null,
                        null
                ));
                continue;
            }
            if ("DENY".equalsIgnoreCase(claim.getRiskDecision())) {
                claim.setStatus(ReferralClaimStatus.REJECTED.name());
                claim.setRejectReason(ReferralRejectReason.ABUSE_RISK.name());
                outcomePublisher.publish(new ReferralOutcomeEvent(
                        claim.getId(),
                        claim.getInviterUserId(),
                        ReferralOutcomeType.REJECTED,
                        claim.getRejectReason(),
                        null,
                        null,
                        null
                ));
                continue;
            }

            MembershipRewardService.RewardGrantResult grant = membershipRewardService.grantReferralReward(claim.getInviterUserId(), claim.getId());
            claim.setStatus(ReferralClaimStatus.SUCCESS.name());
            claim.setRewardedAtUtc(grant.grantedAtUtc());
            outcomePublisher.publish(new ReferralOutcomeEvent(
                    claim.getId(),
                    claim.getInviterUserId(),
                    ReferralOutcomeType.GRANTED,
                    null,
                    grant.oldPremiumUntil(),
                    grant.newPremiumUntil(),
                    grant.grantedAtUtc()
            ));
            log.info("referral_reward_granted claimId={} inviterUserId={}", claim.getId(), claim.getInviterUserId());
        }
    }
}
