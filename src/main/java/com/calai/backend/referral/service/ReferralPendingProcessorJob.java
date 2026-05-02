package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralOutcomeType;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@RequiredArgsConstructor
@Slf4j
@Component
public class ReferralPendingProcessorJob {

    /**
     * If a backend process crashes while a claim is PROCESSING_REWARD, the claim must be
     * returned to PENDING_VERIFICATION so the durable Google defer ledger can be reconciled.
     */
    private static final long STALE_PROCESSING_SECONDS = 30L * 60L;

    private final ReferralClaimRepository claimRepository;
    private final ReferralRewardProcessingTxService txService;
    private final MembershipRewardService membershipRewardService;
    private final ReferralRewardQualificationVerifier qualificationVerifier;
    private final ReferralOutcomePublisher outcomePublisher;

    @Scheduled(fixedDelayString = "${referral.process-pending.fixed-delay:PT10M}")
    public void processPendingVerification() {
        Instant now = Instant.now();

        recoverStaleProcessingClaims(now);

        var claims = claimRepository.findPendingToProcess(now);

        for (var pending : claims) {
            boolean claimed = txService.claimForProcessing(pending.getId(), now);
            if (!claimed) {
                log.info("referral_claim_already_processing claimId={}", pending.getId());
                continue;
            }

            processOneClaim(pending.getId(), now);
        }
    }

    private void recoverStaleProcessingClaims(Instant now) {
        Instant cutoff = now.minusSeconds(STALE_PROCESSING_SECONDS);
        var staleClaims = claimRepository.findStaleProcessingClaims(cutoff);

        for (var claim : staleClaims) {
            log.warn(
                    "referral_processing_stale_recovered claimId={} inviterUserId={} updatedAtUtc={}",
                    claim.getId(),
                    claim.getInviterUserId(),
                    claim.getUpdatedAtUtc()
            );

            /*
             * Important:
             * Do not mark SUCCESS/REJECTED here. Just make it eligible for normal processing.
             * If Google Play defer had already succeeded before the crash, MembershipRewardService
             * will see stale GOOGLE_DEFER_IN_PROGRESS and reconcile by reading Google's current expiry
             * before attempting another defer.
             */
            txService.markPendingAgain(claim.getId());
        }
    }

    private void processOneClaim(Long claimId, Instant now) {
        ReferralClaimEntity claim = txService.loadClaim(claimId);

        if (claim.getRefundDetectedAtUtc() != null) {
            rejectAndNotify(
                    claim,
                    claim.getRejectReason() == null || "NONE".equals(claim.getRejectReason())
                            ? ReferralRejectReason.REFUNDED_OR_REVOKED.name()
                            : claim.getRejectReason()
            );
            return;
        }

        if ("DENY".equalsIgnoreCase(claim.getRiskDecision())) {
            rejectAndNotify(claim, ReferralRejectReason.ABUSE_RISK.name());
            return;
        }

        ReferralRewardQualificationVerifier.VerificationResult verification =
                qualificationVerifier.verifyBeforeReward(claim, now);

        if (verification.retryable()) {
            log.warn(
                    "referral_final_verify_deferred claimId={} inviterUserId={} detail={}",
                    claim.getId(),
                    claim.getInviterUserId(),
                    verification.detail()
            );
            txService.markPendingAgain(claim.getId());
            return;
        }

        if (!verification.qualified()) {
            log.info(
                    "referral_final_verify_rejected claimId={} inviterUserId={} reason={} detail={}",
                    claim.getId(),
                    claim.getInviterUserId(),
                    verification.rejectReason(),
                    verification.detail()
            );
            rejectAndNotify(claim, verification.rejectReason());
            return;
        }

        try {
            MembershipRewardService.RewardGrantResult grant =
                    membershipRewardService.grantReferralReward(claim.getInviterUserId(), claim.getId());

            txService.markSuccess(claim.getId(), grant.grantedAtUtc());

            outcomePublisher.publish(new ReferralOutcomeEvent(
                    claim.getId(),
                    claim.getInviterUserId(),
                    ReferralOutcomeType.GRANTED,
                    null,
                    grant.oldPremiumUntil(),
                    grant.newPremiumUntil(),
                    grant.grantedAtUtc()
            ));

            log.info(
                    "referral_reward_granted claimId={} inviterUserId={}",
                    claim.getId(),
                    claim.getInviterUserId()
            );
        } catch (MembershipRewardService.RewardGrantFinalException ex) {
            log.warn(
                    "referral_reward_failed_final claimId={} inviterUserId={} reason={}",
                    claim.getId(),
                    claim.getInviterUserId(),
                    ex.getMessage()
            );
            rejectAndNotify(claim, ex.rejectReason());
        } catch (MembershipRewardService.RewardGrantDeferredException ex) {
            log.warn(
                    "referral_reward_deferred claimId={} inviterUserId={} reason={}",
                    claim.getId(),
                    claim.getInviterUserId(),
                    ex.getMessage()
            );
            txService.markPendingAgain(claim.getId());
        }
    }

    private void rejectAndNotify(ReferralClaimEntity claim, String reason) {
        String resolvedReason =
                reason == null || reason.isBlank()
                        ? ReferralRejectReason.PURCHASE_NOT_VERIFIED.name()
                        : reason;

        txService.markRejected(claim.getId(), resolvedReason);

        outcomePublisher.publish(new ReferralOutcomeEvent(
                claim.getId(),
                claim.getInviterUserId(),
                ReferralOutcomeType.REJECTED,
                resolvedReason,
                null,
                null,
                null
        ));
    }
}
