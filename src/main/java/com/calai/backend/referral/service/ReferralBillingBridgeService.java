package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralClaimStatus;
import com.calai.backend.referral.domain.ReferralOutcomeType;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Service
public class ReferralBillingBridgeService {

    private static final int VERIFICATION_DAYS = 7;

    private final ReferralClaimRepository claimRepository;
    private final ReferralOutcomePublisher outcomePublisher;
    private final ReferralRiskService referralRiskService;

    @Transactional
    public void onFirstPaidSubscriptionVerified(
            Long inviteeUserId,
            String purchaseTokenHash,
            Instant subscribedAtUtc,
            boolean autoRenewEnabled,
            boolean pending,
            boolean testPurchase
    ) {
        claimRepository.findByInviteeUserId(inviteeUserId).ifPresent(claim -> {
            if (!ReferralClaimStatus.PENDING_SUBSCRIPTION.name().equals(claim.getStatus())) {
                return;
            }

            if (pending) {
                referralRiskService.assessPaidSubscription(
                        claim,
                        purchaseTokenHash,
                        true,
                        testPurchase,
                        subscribedAtUtc
                );

                claim.setPurchaseTokenHash(purchaseTokenHash);
                claim.setSubscribedAtUtc(subscribedAtUtc);
                claim.setAutoRenewStatus(autoRenewEnabled ? "ON" : "OFF");
                claim.setStatus(ReferralClaimStatus.PENDING_SUBSCRIPTION.name());
                claim.setRejectReason(ReferralRejectReason.NONE.name());
                return;
            }

            if (testPurchase) {
                referralRiskService.assessPaidSubscription(claim, purchaseTokenHash, pending, true, subscribedAtUtc);
                reject(claim, ReferralRejectReason.TEST_PURCHASE.name());
                return;
            }

            ReferralRiskService.RiskResult risk = referralRiskService.assessPaidSubscription(
                    claim,
                    purchaseTokenHash,
                    false,
                    false,
                    subscribedAtUtc
            );

            if (risk.denied()) {
                reject(claim, ReferralRejectReason.ABUSE_RISK.name());
                return;
            }

            claim.setPurchaseTokenHash(purchaseTokenHash);
            claim.setSubscribedAtUtc(subscribedAtUtc);
            claim.setQualifiedAtUtc(subscribedAtUtc);
            claim.setVerificationDeadlineUtc(subscribedAtUtc.plusSeconds(VERIFICATION_DAYS * 24L * 3600L));
            claim.setAutoRenewStatus(autoRenewEnabled ? "ON" : "OFF");
            claim.setStatus(ReferralClaimStatus.PENDING_VERIFICATION.name());
            claim.setRejectReason(ReferralRejectReason.NONE.name());
        });
    }

    @Transactional
    public void onPaidSubscriptionNotEligible(
            Long inviteeUserId,
            Instant detectedAtUtc,
            String rejectReason
    ) {
        claimRepository.findByInviteeUserId(inviteeUserId).ifPresent(claim -> {
            if (!ReferralClaimStatus.PENDING_SUBSCRIPTION.name().equals(claim.getStatus())) {
                return;
            }

            claim.setSubscribedAtUtc(detectedAtUtc);
            claim.setQualifiedAtUtc(null);
            claim.setVerificationDeadlineUtc(null);
            reject(claim, rejectReason);
        });
    }

    @Transactional
    public void markRefundedOrRevoked(
            String purchaseTokenHash,
            boolean chargeback,
            Instant detectedAtUtc
    ) {
        claimRepository.findByPurchaseTokenHash(purchaseTokenHash).ifPresent(claim -> {
            if (ReferralClaimStatus.SUCCESS.name().equals(claim.getStatus())) {
                return;
            }

            claim.setRefundDetectedAtUtc(detectedAtUtc);
            claim.setStatus(ReferralClaimStatus.REJECTED.name());
            claim.setRejectReason(chargeback
                    ? ReferralRejectReason.CHARGEBACK.name()
                    : ReferralRejectReason.REFUNDED_OR_REVOKED.name());

            outcomePublisher.publish(new ReferralOutcomeEvent(
                    claim.getId(),
                    claim.getInviterUserId(),
                    ReferralOutcomeType.REJECTED,
                    claim.getRejectReason(),
                    null,
                    null,
                    null
            ));
        });
    }

    private void reject(com.calai.backend.referral.entity.ReferralClaimEntity claim, String reason) {
        claim.setStatus(ReferralClaimStatus.REJECTED.name());
        claim.setRejectReason(reason);
        outcomePublisher.publish(new ReferralOutcomeEvent(
                claim.getId(),
                claim.getInviterUserId(),
                ReferralOutcomeType.REJECTED,
                claim.getRejectReason(),
                null,
                null,
                null
        ));
    }
}
