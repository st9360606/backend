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
                claim.setStatus(ReferralClaimStatus.REJECTED.name());
                claim.setRejectReason(ReferralRejectReason.PURCHASE_PENDING.name());
                outcomePublisher.publish(new ReferralOutcomeEvent(
                        claim.getId(),
                        claim.getInviterUserId(),
                        ReferralOutcomeType.REJECTED,
                        claim.getRejectReason(),
                        null,
                        null,
                        null
                ));
                return;
            }

            if (testPurchase) {
                claim.setStatus(ReferralClaimStatus.REJECTED.name());
                claim.setRejectReason(ReferralRejectReason.TEST_PURCHASE.name());
                outcomePublisher.publish(new ReferralOutcomeEvent(
                        claim.getId(),
                        claim.getInviterUserId(),
                        ReferralOutcomeType.REJECTED,
                        claim.getRejectReason(),
                        null,
                        null,
                        null
                ));
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
}
