package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.BillingProductProperties;
import com.calai.backend.entitlement.service.PurchaseTokenCrypto;
import com.calai.backend.entitlement.service.SubscriptionVerifier;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;

@RequiredArgsConstructor
@Slf4j
@Service
public class ReferralRewardQualificationVerifier {

    public record VerificationResult(
            boolean qualified,
            boolean retryable,
            String rejectReason,
            String detail
    ) {
        /**
         * 注意：
         * record 欄位 boolean qualified 會自動產生 qualified() accessor。
         * 所以 static factory 不可以也叫 qualified()，否則會跟 accessor 衝突。
         */
        public static VerificationResult success() {
            return new VerificationResult(true, false, null, null);
        }

        public static VerificationResult retryLater(String detail) {
            return new VerificationResult(false, true, null, detail);
        }

        public static VerificationResult reject(String rejectReason, String detail) {
            return new VerificationResult(false, false, rejectReason, detail);
        }
    }

    private final UserEntitlementRepository entitlementRepository;
    private final PurchaseTokenCrypto purchaseTokenCrypto;
    private final SubscriptionVerifier subscriptionVerifier;
    private final BillingProductProperties productProps;
    private final ObjectProvider<VoidedPurchaseChecker> voidedPurchaseCheckerProvider;
    /**
     * Referral v1.3 final verification:
     * 7 天冷卻期結束、實際發獎前，重新查 Google Play，避免 RTDN / voided purchase
     * 延遲或漏接時把退款、revoke、chargeback、pending、trial 誤判成成功。
     */
    public VerificationResult verifyBeforeReward(ReferralClaimEntity claim, Instant now) {
        if (claim.getPurchaseTokenHash() == null || claim.getPurchaseTokenHash().isBlank()) {
            return VerificationResult.reject(
                    ReferralRejectReason.PURCHASE_NOT_VERIFIED.name(),
                    "Referral claim has no purchaseTokenHash"
            );
        }

        UserEntitlementEntity entitlement = entitlementRepository
                .findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(claim.getPurchaseTokenHash())
                .orElse(null);

        if (entitlement == null || !claim.getInviteeUserId().equals(entitlement.getUserId())) {
            return VerificationResult.reject(
                    ReferralRejectReason.PURCHASE_NOT_VERIFIED.name(),
                    "No matching invitee Google Play entitlement was found"
            );
        }

        String rawPurchaseToken = purchaseTokenCrypto.decryptOrNull(entitlement.getPurchaseTokenCiphertext());
        if (rawPurchaseToken == null || rawPurchaseToken.isBlank()) {
            return VerificationResult.reject(
                    ReferralRejectReason.PURCHASE_NOT_VERIFIED.name(),
                    "Invitee purchase token cannot be decrypted for final verification"
            );
        }

        SubscriptionVerifier.VerifiedSubscription verified;
        try {
            verified = subscriptionVerifier.verify(rawPurchaseToken);
        } catch (Exception ex) {
            log.warn(
                    "referral_final_verify_retryable_failed claimId={} inviteeUserId={} tokenHash={} error={}",
                    claim.getId(),
                    claim.getInviteeUserId(),
                    claim.getPurchaseTokenHash(),
                    ex.toString()
            );
            return VerificationResult.retryLater("Google Play final verification failed: " + ex);
        }

        if (verified.testPurchase()) {
            return VerificationResult.reject(
                    ReferralRejectReason.TEST_PURCHASE.name(),
                    "Google Play test purchases do not qualify"
            );
        }

        if (verified.pending()) {
            return VerificationResult.reject(
                    ReferralRejectReason.PURCHASE_PENDING.name(),
                    "Google Play subscription is still pending"
            );
        }

        if (verified.freeTrial()) {
            return VerificationResult.reject(
                    ReferralRejectReason.PURCHASE_NOT_VERIFIED.name(),
                    "Google Play free trial is not a paid subscription"
            );
        }

        if (!verified.active() || verified.expiryTimeUtc() == null || !verified.expiryTimeUtc().isAfter(now)) {
            return VerificationResult.reject(
                    ReferralRejectReason.REFUNDED_OR_REVOKED.name(),
                    "Google Play subscription is not active at reward time"
            );
        }

        VoidedPurchaseChecker voidedPurchaseChecker = voidedPurchaseCheckerProvider.getIfAvailable();

        if (voidedPurchaseChecker != null) {
            try {
                boolean voided = voidedPurchaseChecker.isVoidedSubscriptionPurchase(
                        rawPurchaseToken,
                        claim.getSubscribedAtUtc(),
                        now
                );

                if (voided) {
                    return VerificationResult.reject(
                            ReferralRejectReason.REFUNDED_OR_REVOKED.name(),
                            "Google Play voided purchase was found during referral cooldown"
                    );
                }
            } catch (Exception ex) {
                log.warn(
                        "referral_voided_purchase_check_retryable_failed claimId={} inviteeUserId={} tokenHash={} error={}",
                        claim.getId(),
                        claim.getInviteeUserId(),
                        claim.getPurchaseTokenHash(),
                        ex.toString()
                );
                return VerificationResult.retryLater("Google Play voided purchase check failed: " + ex);
            }
        }

        String tier = productProps.toTierOrNull(verified.productId());
        if (tier == null) {
            return VerificationResult.reject(
                    ReferralRejectReason.PURCHASE_NOT_VERIFIED.name(),
                    "Google Play productId is not configured as a paid membership product"
            );
        }

        entitlement.setLastVerifiedAtUtc(now);
        entitlement.setLastGoogleVerifiedAtUtc(now);
        entitlement.setValidToUtc(verified.expiryTimeUtc());
        entitlement.setSubscriptionState(verified.subscriptionState());
        entitlement.setAutoRenewEnabled(verified.autoRenewEnabled());
        entitlement.setAcknowledgementState(verified.acknowledgementState());
        entitlement.setLatestOrderId(verified.latestOrderId());
        entitlement.setUpdatedAtUtc(now);
        entitlementRepository.save(entitlement);

        return VerificationResult.success();
    }
}
