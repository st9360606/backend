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
            if (token == null || token.isBlank()) {
                continue;
            }

            String tokenHash = sha256Hex(token);

            SubscriptionVerifier.VerifiedSubscription v;
            try {
                v = verifier.verify(token);
            } catch (Exception ex) {
                /*
                 * Google Play 驗證 API 失敗時，不要直接關閉 entitlement。
                 * 原因：
                 * - 可能只是暫時網路錯誤
                 * - 可能 Google Play API 暫時不可用
                 * - 若此時誤關閉，會造成有效付費用戶被降級
                 */
                log.warn(
                        "entitlement_sync_verify_failed userId={} productId={} tokenHash={} error={}",
                        userId,
                        p == null ? null : p.productId(),
                        tokenHash,
                        ex.toString()
                );
                continue;
            }

            /*
             * ✅ 關鍵修正：
             * 如果 client 帶上來的 purchaseToken 經 Google Play 驗證後已經不是 active，
             * 要主動關閉 DB 內同 tokenHash 的舊 ACTIVE entitlement。
             *
             * 這可以補 RTDN 延遲或漏接時的風險：
             * - 月訂閱過期
             * - 年訂閱過期
             * - 月訂閱退款撤銷
             * - 年訂閱退款撤銷
             */
            if (!v.active() || v.expiryTimeUtc() == null || v.productId() == null) {
                entitlementRepo.closeActiveByPurchaseTokenHash(
                        tokenHash,
                        "EXPIRED",
                        now,
                        null,
                        now
                );

                log.info(
                        "entitlement_sync_inactive_token_closed userId={} tokenHash={} subscriptionState={} expiryTimeUtc={} productId={}",
                        userId,
                        tokenHash,
                        v.subscriptionState(),
                        v.expiryTimeUtc(),
                        v.productId()
                );

                continue;
            }

            String tier = productProps.toTierOrNull(v.productId());
            if (tier == null) {
                log.warn(
                        "entitlement_sync_unknown_product userId={} productId={} tokenHash={}",
                        userId,
                        v.productId(),
                        tokenHash
                );
                continue;
            }

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

        boolean newlyCreated = false;
        String previousType = e == null ? null : e.getEntitlementType();

        if (e != null && !userId.equals(e.getUserId())) {
            log.warn(
                    "purchase_token_already_bound requestedUserId={} ownerUserId={} productId={} tokenHash={}",
                    userId,
                    e.getUserId(),
                    v.productId(),
                    purchaseTokenHash
            );
            throw new IllegalStateException("PURCHASE_TOKEN_ALREADY_BOUND");
        }

        if (e == null) {
            newlyCreated = true;

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

        if (ackOk) {
            saved.setAcknowledgementState("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
            saved.setUpdatedAtUtc(now);

            /*
             * expireActiveByUserIdExcept 使用 clearAutomatically=true，
             * saved 可能已 detached，因此這裡明確 save，避免 ack 狀態沒有持久化。
             */
            entitlementRepo.save(saved);
        } else if (!"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(v.acknowledgementState())) {
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
        boolean becamePaid =
                !v.freeTrial()
                        && (
                        newlyCreated
                                || "TRIAL".equalsIgnoreCase(previousType)
                );

        if (allowReferralUpdate && becamePaid) {
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
                    null,
                    false
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
                    calcDaysLeft(now, validTo),
                    false
            );
        }

        boolean paymentIssue =
                "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(e.getSubscriptionState());

        return new EntitlementSyncResponse(
                "ACTIVE",
                type,
                "PREMIUM",
                validTo,
                null,
                null,
                paymentIssue
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
        return syncPurchaseTokenFromRtdn(
                purchaseToken,
                eventTime,
                "EXPIRED"
        );
    }

    @Transactional
    public EntitlementSyncResponse syncPurchaseTokenFromRtdn(
            String purchaseToken,
            Instant eventTime,
            String inactiveCloseStatus
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
            String closeStatus = "REVOKED".equalsIgnoreCase(inactiveCloseStatus)
                    ? "REVOKED"
                    : "EXPIRED";

            Instant effectiveEventTime = eventTime == null ? now : eventTime;
            Instant revokedAt = "REVOKED".equals(closeStatus)
                    ? effectiveEventTime
                    : null;

            log.info(
                    "rtdn_linked_token_inactive_close_previous userId={} tokenHash={} linkedHash={} subscriptionState={} closeStatus={}",
                    previous.getUserId(),
                    tokenHash,
                    linkedHash,
                    verified.subscriptionState(),
                    closeStatus
            );

            entitlementRepo.closeActiveByPurchaseTokenHash(
                    linkedHash,
                    closeStatus,
                    now,
                    revokedAt,
                    effectiveEventTime
            );

            entitlementRepo.closeActiveByPurchaseTokenHash(
                    tokenHash,
                    closeStatus,
                    now,
                    revokedAt,
                    effectiveEventTime
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
