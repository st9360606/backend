package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.dto.EntitlementSyncRequest;
import com.calai.backend.entitlement.dto.EntitlementSyncResponse;
import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.service.ReferralBillingBridgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
@Service
public class EntitlementSyncService {

    private final SubscriptionVerifier verifier;
    private final UserEntitlementRepository entitlementRepo;
    private final BillingProductProperties productProps;
    private final ReferralBillingBridgeService referralBillingBridgeService;
    private final PurchaseAcknowledger purchaseAcknowledger;

    @Transactional
    public EntitlementSyncResponse sync(Long userId, EntitlementSyncRequest req) {
        Instant now = Instant.now();

        if (req == null || req.purchases() == null || req.purchases().isEmpty()) {
            return buildSummaryResponse(userId, now);
        }

        VerifiedCandidate best = null;

        for (var p : req.purchases()) {
            String token = (p == null) ? null : p.purchaseToken();
            if (token == null || token.isBlank()) continue;

            SubscriptionVerifier.VerifiedSubscription v;
            try {
                v = verifier.verify(token);
            } catch (Exception ex) {
                log.warn("entitlement_sync_verify_failed userId={} productId={} error={}",
                        userId, p == null ? null : p.productId(), ex.toString());
                continue;
            }

            if (!v.active() || v.expiryTimeUtc() == null || v.productId() == null) {
                continue;
            }

            String tier = productProps.toTierOrNull(v.productId());
            if (tier == null) {
                log.warn("entitlement_sync_unknown_product userId={} productId={}", userId, v.productId());
                continue;
            }

            String tokenHash = sha256Hex(token);
            if (best == null || v.expiryTimeUtc().isAfter(best.verified().expiryTimeUtc())) {
                best = new VerifiedCandidate(tokenHash, token, tier, v);
            }
        }

        if (best == null) {
            return buildSummaryResponse(userId, now);
        }

        upsertGooglePlayEntitlement(
                userId,
                best.purchaseTokenHash(),
                best.rawPurchaseToken(),
                best.tier(),
                best.verified(),
                now,
                null,
                true
        );

        return buildSummaryResponse(userId, now);
    }

    /**
     * RTDN 專用：RTDN 只告訴你 purchaseToken 狀態有變，完整狀態仍要回查 Google Play。
     */
    @Transactional
    public EntitlementSyncResponse syncKnownPurchaseTokenFromRtdn(
            Long userId,
            String purchaseToken,
            Instant eventTime
    ) {
        Instant now = Instant.now();
        String tokenHash = sha256Hex(purchaseToken);

        SubscriptionVerifier.VerifiedSubscription v;
        try {
            v = verifier.verify(purchaseToken);
        } catch (Exception ex) {
            log.warn("entitlement_rtdn_verify_failed userId={} tokenHash={} error={}",
                    userId, tokenHash, ex.toString());
            return buildSummaryResponse(userId, now);
        }

        if (!v.active() || v.expiryTimeUtc() == null || v.productId() == null) {
            entitlementRepo.closeActiveByPurchaseTokenHash(
                    tokenHash,
                    "EXPIRED",
                    now,
                    null,
                    eventTime == null ? now : eventTime
            );
            return buildSummaryResponse(userId, now);
        }

        String tier = productProps.toTierOrNull(v.productId());
        if (tier == null) {
            log.warn("entitlement_rtdn_unknown_product userId={} tokenHash={} productId={}",
                    userId, tokenHash, v.productId());
            return buildSummaryResponse(userId, now);
        }

        upsertGooglePlayEntitlement(
                userId,
                tokenHash,
                purchaseToken,
                tier,
                v,
                now,
                eventTime,
                true
        );

        return buildSummaryResponse(userId, now);
    }

    @Transactional
    public void closeByPurchaseTokenHash(String purchaseTokenHash, String status, Instant eventTime) {
        Instant now = Instant.now();
        entitlementRepo.closeActiveByPurchaseTokenHash(
                purchaseTokenHash,
                status,
                now,
                "REVOKED".equals(status) ? (eventTime == null ? now : eventTime) : null,
                eventTime == null ? now : eventTime
        );
    }

    @Transactional(readOnly = true)
    public EntitlementSyncResponse me(Long userId) {
        return buildSummaryResponse(userId, Instant.now());
    }

    private void upsertGooglePlayEntitlement(
            Long userId,
            String purchaseTokenHash,
            String rawPurchaseToken,
            String tier,
            SubscriptionVerifier.VerifiedSubscription v,
            Instant now,
            Instant rtdnEventTime,
            boolean allowReferralUpdate
    ) {
        String entitlementTypeToStore = v.freeTrial() ? "TRIAL" : tier;
        String linkedPurchaseTokenHash = v.linkedPurchaseToken() == null || v.linkedPurchaseToken().isBlank()
                ? null
                : sha256Hex(v.linkedPurchaseToken());

        UserEntitlementEntity e = entitlementRepo
                .findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(purchaseTokenHash)
                .orElse(null);

        if (e != null && !userId.equals(e.getUserId())) {
            log.warn(
                    "purchase_token_already_bound requestedUserId={} ownerUserId={} productId={} tokenHash={}",
                    userId,
                    e.getUserId(),
                    v.productId(),
                    purchaseTokenHash
            );
            return;
        }

        if (e == null) {
            e = new UserEntitlementEntity();
            e.setId(UUID.randomUUID().toString());
            e.setUserId(userId);
            e.setPurchaseTokenHash(purchaseTokenHash);
            e.setCreatedAtUtc(now);
        }

        e.setEntitlementType(entitlementTypeToStore);
        e.setStatus("ACTIVE");
        e.setValidFromUtc(now);
        e.setValidToUtc(v.expiryTimeUtc());
        e.setLastVerifiedAtUtc(now);
        e.setSource("GOOGLE_PLAY");
        e.setProductId(v.productId());
        e.setSubscriptionState(v.subscriptionState());
        e.setOfferPhase(v.offerPhase());
        e.setAutoRenewEnabled(v.autoRenewEnabled());
        e.setAcknowledgementState(v.acknowledgementState());
        e.setLatestOrderId(v.latestOrderId());
        e.setLinkedPurchaseTokenHash(linkedPurchaseTokenHash);
        e.setLastRtdnAtUtc(rtdnEventTime);
        e.setUpdatedAtUtc(now);

        UserEntitlementEntity saved = entitlementRepo.save(e);

        entitlementRepo.expireActiveByUserIdExcept(
                userId,
                saved.getId(),
                now
        );

        boolean ackOk = purchaseAcknowledger.acknowledgeWithRetry(
                v.productId(),
                rawPurchaseToken,
                v.acknowledgementState()
        );

        if (!ackOk && !"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(v.acknowledgementState())) {
            log.warn(
                    "google_play_ack_not_confirmed userId={} productId={} tokenHash={} acknowledgementState={}",
                    userId,
                    v.productId(),
                    purchaseTokenHash,
                    v.acknowledgementState()
            );
        }

        /*
         * Play free trial 不算首次有效付費。
         * 等 trial 結束後 Google Play 真正續訂付費，freeTrial=false 才觸發 referral verification。
         */
        if (allowReferralUpdate && !v.freeTrial()) {
            referralBillingBridgeService.onFirstPaidSubscriptionVerified(
                    userId,
                    purchaseTokenHash,
                    now,
                    v.autoRenewEnabled(),
                    v.pending(),
                    v.testPurchase()
            );
        }
    }

    private EntitlementSyncResponse buildSummaryResponse(Long userId, Instant now) {
        var activeList = entitlementRepo.findActiveBestFirst(userId, now, PageRequest.of(0, 1));

        if (activeList.isEmpty()) {
            return new EntitlementSyncResponse(
                    "INACTIVE",
                    null,
                    "FREE",
                    null,
                    null,
                    null
            );
        }

        UserEntitlementEntity e = activeList.get(0);
        String type = e.getEntitlementType();
        Instant validTo = e.getValidToUtc();

        if ("TRIAL".equalsIgnoreCase(type)) {
            return new EntitlementSyncResponse(
                    "ACTIVE",
                    "TRIAL",
                    "TRIAL",
                    validTo,
                    validTo,
                    calcDaysLeft(now, validTo)
            );
        }

        return new EntitlementSyncResponse(
                "ACTIVE",
                type,
                "PREMIUM",
                validTo,
                null,
                null
        );
    }

    private record VerifiedCandidate(
            String purchaseTokenHash,
            String rawPurchaseToken,
            String tier,
            SubscriptionVerifier.VerifiedSubscription verified
    ) {}

    private static int calcDaysLeft(Instant now, Instant end) {
        if (end == null || !end.isAfter(now)) return 0;
        long seconds = Duration.between(now, end).getSeconds();
        return (int) Math.max(1, Math.ceil(seconds / 86_400.0));
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA256_FAILED", e);
        }
    }

    @Transactional
    public EntitlementSyncResponse syncPurchaseTokenFromRtdn(
            String purchaseToken,
            Instant eventTime
    ) {
        Instant now = Instant.now();

        if (purchaseToken == null || purchaseToken.isBlank()) {
            return null;
        }

        String tokenHash = sha256Hex(purchaseToken);

        var existing = entitlementRepo
                .findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(tokenHash)
                .orElse(null);

        if (existing != null) {
            return syncKnownPurchaseTokenFromRtdn(
                    existing.getUserId(),
                    purchaseToken,
                    eventTime
            );
        }

        SubscriptionVerifier.VerifiedSubscription verified;
        try {
            verified = verifier.verify(purchaseToken);
        } catch (Exception ex) {
            log.warn(
                    "rtdn_unbound_token_verify_failed tokenHash={} error={}",
                    tokenHash,
                    ex.toString()
            );
            return null;
        }

        String linkedToken = verified.linkedPurchaseToken();
        if (linkedToken == null || linkedToken.isBlank()) {
            log.warn(
                    "rtdn_purchase_token_not_bound tokenHash={} productId={} latestOrderId={}",
                    tokenHash,
                    verified.productId(),
                    verified.latestOrderId()
            );
            return null;
        }

        String linkedHash = sha256Hex(linkedToken);

        var previous = entitlementRepo
                .findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(linkedHash)
                .or(() -> entitlementRepo.findTopByLinkedPurchaseTokenHashOrderByUpdatedAtUtcDesc(linkedHash))
                .orElse(null);

        if (previous == null) {
            log.warn(
                    "rtdn_linked_purchase_token_not_bound tokenHash={} linkedHash={} productId={}",
                    tokenHash,
                    linkedHash,
                    verified.productId()
            );
            return null;
        }

        if (!verified.active() || verified.expiryTimeUtc() == null || verified.productId() == null) {
            log.info(
                    "rtdn_linked_token_inactive userId={} tokenHash={} linkedHash={} subscriptionState={}",
                    previous.getUserId(),
                    tokenHash,
                    linkedHash,
                    verified.subscriptionState()
            );

            return buildSummaryResponse(previous.getUserId(), now);
        }

        String tier = productProps.toTierOrNull(verified.productId());
        if (tier == null) {
            log.warn(
                    "rtdn_unknown_product_for_linked_token userId={} productId={} tokenHash={}",
                    previous.getUserId(),
                    verified.productId(),
                    tokenHash
            );
            return buildSummaryResponse(previous.getUserId(), now);
        }

        upsertGooglePlayEntitlement(
                previous.getUserId(),
                tokenHash,
                purchaseToken,
                tier,
                verified,
                now,
                eventTime,
                true
        );

        return buildSummaryResponse(previous.getUserId(), now);
    }
}
