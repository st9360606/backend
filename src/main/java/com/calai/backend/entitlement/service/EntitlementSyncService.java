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
    private final PurchaseTokenCrypto purchaseTokenCrypto;

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
                /*
                 * Referral v1.3 pending purchase fix:
                 * Google Play pending purchase is not active yet, so it enters this inactive branch.
                 * Still, we must persist the purchaseTokenHash on referral_claims so a later
                 * SUBSCRIPTION_PENDING_PURCHASE_CANCELED RTDN can find and reject the claim.
                 */
                recordPendingReferralPurchaseIfNeeded(
                        userId,
                        tokenHash,
                        v,
                        now,
                        "client_sync"
                );

                entitlementRepo.closeActiveByPurchaseTokenHash(
                        tokenHash,
                        inactiveCloseStatus(v.subscriptionState()),
                        now,
                        inactiveRevokedAt(v.subscriptionState(), now),
                        now,
                        v.subscriptionState(),
                        paymentState(v.subscriptionState()),
                        inactiveCloseReason(v.subscriptionState()),
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
            Instant effectiveEventTime = eventTime == null ? now : eventTime;

            /*
             * Referral v1.3 pending purchase fix for RTDN-known token:
             * pending is not active, but referral must remember the token hash.
             */
            recordPendingReferralPurchaseIfNeeded(
                    userId,
                    tokenHash,
                    v,
                    effectiveEventTime,
                    "rtdn_known_token"
            );

            entitlementRepo.closeActiveByPurchaseTokenHash(
                    tokenHash,
                    inactiveCloseStatus(v.subscriptionState()),
                    now,
                    inactiveRevokedAt(v.subscriptionState(), effectiveEventTime),
                    effectiveEventTime,
                    v.subscriptionState(),
                    paymentState(v.subscriptionState()),
                    inactiveCloseReason(v.subscriptionState()),
                    now
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
        Instant effectiveEventTime = eventTime == null ? now : eventTime;

        String paymentState = "REVOKED".equalsIgnoreCase(status)
                ? "REVOKED"
                : "EXPIRED";

        String closeReason = "REVOKED".equalsIgnoreCase(status)
                ? "GOOGLE_PLAY_REVOKED"
                : "GOOGLE_PLAY_EXPIRED";

        entitlementRepo.closeActiveByPurchaseTokenHash(
                purchaseTokenHash,
                status,
                now,
                "REVOKED".equalsIgnoreCase(status) ? effectiveEventTime : null,
                effectiveEventTime,
                null,
                paymentState,
                closeReason,
                now
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

        /*
         * Referral v1.3:
         * 必須是 lifetime first paid subscription 才可觸發 referral verification。
         * 這個查詢一定要在本次 entitlement save 之前執行，
         * 否則新 paid row 會把自己算成 prior paid history。
         */
        boolean hadAnyPriorGooglePlayPaidHistory =
                entitlementRepo.existsAnyGooglePlayPaidSubscriptionHistory(userId);

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
        e.setLastGoogleVerifiedAtUtc(now);
        e.setPurchaseTokenCiphertext(purchaseTokenCrypto.encryptOrNull(rawPurchaseToken));
        e.setSource("GOOGLE_PLAY");
        e.setProductId(v.productId());
        e.setSubscriptionState(v.subscriptionState());
        e.setPaymentState(paymentState(v.subscriptionState()));
        e.setGraceUntilUtc(null);
        e.setCloseReason(null);
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
                        && !hadAnyPriorGooglePlayPaidHistory
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
        } else if (allowReferralUpdate
                && !v.freeTrial()
                && hadAnyPriorGooglePlayPaidHistory
                && (newlyCreated || "TRIAL".equalsIgnoreCase(previousType))) {
            referralBillingBridgeService.onPaidSubscriptionNotEligible(
                    userId,
                    now,
                    "INVITEE_ALREADY_SUBSCRIBED"
            );
        }
    }

    /**
     * Referral v1.3:
     * Google Play pending purchase is not an active paid subscription yet, so it must not start
     * the 7-day verification window and must not grant rewards. However, we still persist the
     * purchaseTokenHash on the pending referral claim so a later
     * SUBSCRIPTION_PENDING_PURCHASE_CANCELED RTDN can find and reject the claim.
     *
     * Important: SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED is intentionally excluded here.
     * That terminal state is handled by RTDN close/revoke flow and must not be converted back
     * into PENDING_SUBSCRIPTION.
     */
    private void recordPendingReferralPurchaseIfNeeded(
            Long userId,
            String purchaseTokenHash,
            SubscriptionVerifier.VerifiedSubscription v,
            Instant detectedAtUtc,
            String source
    ) {
        if (userId == null || purchaseTokenHash == null || purchaseTokenHash.isBlank() || v == null) {
            return;
        }

        if (!isPendingPurchaseState(v.subscriptionState())) {
            return;
        }

        Instant resolvedDetectedAtUtc = detectedAtUtc == null ? Instant.now() : detectedAtUtc;

        referralBillingBridgeService.onFirstPaidSubscriptionVerified(
                userId,
                purchaseTokenHash,
                resolvedDetectedAtUtc,
                v.autoRenewEnabled(),
                true,
                v.testPurchase()
        );

        log.info(
                "referral_pending_purchase_recorded userId={} tokenHash={} subscriptionState={} source={}",
                userId,
                purchaseTokenHash,
                v.subscriptionState(),
                source
        );
    }

    static boolean isPendingPurchaseState(String state) {
        return "SUBSCRIPTION_STATE_PENDING".equals(state);
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
                    isTrialEligible(userId),
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
                    false,
                    false
            );
        }

        boolean paymentIssue = isPaymentIssueState(e.getSubscriptionState());

        return new EntitlementSyncResponse(
                "ACTIVE",
                type,
                "PREMIUM",
                validTo,
                null,
                null,
                isTrialEligible(userId),
                paymentIssue
        );
    }

    private record VerifiedCandidate(
            String purchaseTokenHash,
            String rawPurchaseToken,
            String tier,
            SubscriptionVerifier.VerifiedSubscription verified
    ) {}

    private boolean isTrialEligible(Long userId) {
        return !entitlementRepo.existsAnyTrialHistory(userId);
    }


    static boolean isPaymentIssueState(String state) {
        return "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(state);
    }

    static boolean isHardBlockedState(String state) {
        return "SUBSCRIPTION_STATE_PENDING".equals(state)
                || "SUBSCRIPTION_STATE_ON_HOLD".equals(state)
                || "SUBSCRIPTION_STATE_EXPIRED".equals(state)
                || "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED".equals(state)
                || "SUBSCRIPTION_STATE_PAUSED".equals(state);
    }

    static String paymentState(String state) {
        if ("SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(state)) return "GRACE";
        if ("SUBSCRIPTION_STATE_ON_HOLD".equals(state)) return "ON_HOLD";
        if ("SUBSCRIPTION_STATE_EXPIRED".equals(state)) return "EXPIRED";
        if ("SUBSCRIPTION_STATE_REVOKED".equals(state)) return "REVOKED";
        if ("SUBSCRIPTION_STATE_PENDING".equals(state)) return "PENDING";
        if ("SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED".equals(state)) return "PENDING_PURCHASE_CANCELED";
        if ("SUBSCRIPTION_STATE_PAUSED".equals(state)) return "ON_HOLD";
        if ("SUBSCRIPTION_STATE_CANCELED".equals(state)) return "OK";
        if ("SUBSCRIPTION_STATE_ACTIVE".equals(state)) return "OK";
        return "UNKNOWN";
    }

    static String inactiveCloseStatus(String state) {
        if ("SUBSCRIPTION_STATE_REVOKED".equals(state)) return "REVOKED";
        return "EXPIRED";
    }

    static String inactiveCloseReason(String state) {
        if ("SUBSCRIPTION_STATE_ON_HOLD".equals(state)) return "GOOGLE_PLAY_ON_HOLD";
        if ("SUBSCRIPTION_STATE_EXPIRED".equals(state)) return "GOOGLE_PLAY_EXPIRED";
        if ("SUBSCRIPTION_STATE_PENDING".equals(state)) return "GOOGLE_PLAY_PENDING_PURCHASE";
        if ("SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED".equals(state)) return "GOOGLE_PLAY_PENDING_PURCHASE_CANCELED";
        if ("SUBSCRIPTION_STATE_PAUSED".equals(state)) return "GOOGLE_PLAY_PAUSED";
        if ("SUBSCRIPTION_STATE_REVOKED".equals(state)) return "GOOGLE_PLAY_REVOKED";
        if (state == null || state.isBlank()) return "GOOGLE_PLAY_INACTIVE_UNKNOWN";
        return "GOOGLE_PLAY_INACTIVE_" + state;
    }

    static Instant inactiveRevokedAt(String state, Instant eventTime) {
        return "SUBSCRIPTION_STATE_REVOKED".equals(state) ? eventTime : null;
    }

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

            String closePaymentState = "REVOKED".equals(closeStatus)
                    ? "REVOKED"
                    : paymentState(verified.subscriptionState());

            String closeReason = "REVOKED".equals(closeStatus)
                    ? "GOOGLE_PLAY_REVOKED"
                    : inactiveCloseReason(verified.subscriptionState());

            /*
             * Referral v1.3 pending purchase fix for linked RTDN tokens:
             * If Google reports the new token as pending and it can be linked back to a known user,
             * persist the pending token hash on the referral claim before returning.
             */
            recordPendingReferralPurchaseIfNeeded(
                    previous.getUserId(),
                    tokenHash,
                    verified,
                    effectiveEventTime,
                    "rtdn_linked_token"
            );

            if (isPendingPurchaseState(verified.subscriptionState())) {
                log.info(
                        "rtdn_linked_pending_token_recorded_without_closing_previous userId={} tokenHash={} linkedHash={} subscriptionState={}",
                        previous.getUserId(),
                        tokenHash,
                        linkedHash,
                        verified.subscriptionState()
                );
                return buildSummaryResponse(previous.getUserId(), now);
            }

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
                    effectiveEventTime,
                    verified.subscriptionState(),
                    closePaymentState,
                    closeReason,
                    now
            );

            entitlementRepo.closeActiveByPurchaseTokenHash(
                    tokenHash,
                    closeStatus,
                    now,
                    revokedAt,
                    effectiveEventTime,
                    verified.subscriptionState(),
                    closePaymentState,
                    closeReason,
                    now
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

    @Transactional
    public void retryAcknowledgeForStoredToken(String entitlementId, Instant now) {
        UserEntitlementEntity e = entitlementRepo.findById(entitlementId).orElse(null);
        if (e == null) return;

        if (!"GOOGLE_PLAY".equals(e.getSource())) return;
        if (!"ACTIVE".equals(e.getStatus())) return;
        if ("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(e.getAcknowledgementState())) return;

        String token = purchaseTokenCrypto.decryptOrNull(e.getPurchaseTokenCiphertext());
        if (token == null || token.isBlank()) {
            log.warn(
                    "ack_retry_skipped_token_missing entitlementId={} userId={} tokenHash={}",
                    e.getId(),
                    e.getUserId(),
                    e.getPurchaseTokenHash()
            );
            return;
        }

        boolean ackOk = purchaseAcknowledger.acknowledgeWithRetry(
                e.getProductId(),
                token,
                e.getAcknowledgementState()
        );

        if (ackOk) {
            e.setAcknowledgementState("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
            e.setUpdatedAtUtc(now);
            entitlementRepo.save(e);
        }
    }
}
