package com.calai.backend.entitlement.rtdn;

import com.calai.backend.entitlement.service.EntitlementSyncService;
import com.calai.backend.referral.service.ReferralBillingBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@RequiredArgsConstructor
@Slf4j
@Service
public class GoogleRtdnService {

    private static final int SUBSCRIPTION_RECOVERED = 1;
    private static final int SUBSCRIPTION_RENEWED = 2;
    private static final int SUBSCRIPTION_CANCELED = 3;
    private static final int SUBSCRIPTION_PURCHASED = 4;
    private static final int SUBSCRIPTION_ON_HOLD = 5;
    private static final int SUBSCRIPTION_IN_GRACE_PERIOD = 6;
    private static final int SUBSCRIPTION_RESTARTED = 7;
    private static final int SUBSCRIPTION_DEFERRED = 9;
    private static final int SUBSCRIPTION_PAUSED = 10;
    private static final int SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED = 11;
    private static final int SUBSCRIPTION_REVOKED = 12;
    private static final int SUBSCRIPTION_EXPIRED = 13;
    private static final int SUBSCRIPTION_PENDING_PURCHASE_CANCELED = 20;

    private final ObjectMapper objectMapper;
    private final ReferralBillingBridgeService referralBillingBridgeService;
    private final EntitlementSyncService entitlementSyncService;

    public void handlePubSubMessage(String base64Data, String messageId) {
        if (base64Data == null || base64Data.isBlank()) {
            log.warn("rtdn_empty_data messageId={}", messageId);
            return;
        }

        try {
            String json = new String(
                    Base64.getDecoder().decode(base64Data),
                    StandardCharsets.UTF_8
            );

            JsonNode root = objectMapper.readTree(json);
            Instant eventTime = resolveEventTime(root);

            if (root.hasNonNull("subscriptionNotification")) {
                handleSubscriptionNotification(
                        root.path("subscriptionNotification"),
                        eventTime,
                        messageId
                );
                return;
            }

            if (root.hasNonNull("voidedPurchaseNotification")) {
                handleVoidedPurchaseNotification(
                        root.path("voidedPurchaseNotification"),
                        eventTime,
                        messageId
                );
                return;
            }

            if (root.hasNonNull("testNotification")) {
                log.info("rtdn_test_notification messageId={}", messageId);
                return;
            }

            log.info("rtdn_ignored messageId={} payload={}", messageId, json);
        } catch (Exception ex) {
            log.warn("rtdn_handle_failed messageId={} error={}", messageId, ex.toString());
            throw new IllegalArgumentException("RTDN_HANDLE_FAILED", ex);
        }
    }

    private void handleSubscriptionNotification(
            JsonNode subscription,
            Instant eventTime,
            String messageId
    ) {
        int notificationType = subscription.path("notificationType").asInt(-1);
        String purchaseToken = subscription.path("purchaseToken").asText(null);

        if (purchaseToken == null || purchaseToken.isBlank()) {
            log.warn(
                    "rtdn_subscription_missing_purchase_token messageId={} type={}",
                    messageId,
                    notificationType
            );
            return;
        }

        String tokenHash = EntitlementSyncService.sha256Hex(purchaseToken);

        switch (notificationType) {
            case SUBSCRIPTION_PURCHASED,
                 SUBSCRIPTION_RENEWED,
                 SUBSCRIPTION_RECOVERED,
                 SUBSCRIPTION_CANCELED,
                 SUBSCRIPTION_RESTARTED,
                 SUBSCRIPTION_DEFERRED,
                 SUBSCRIPTION_IN_GRACE_PERIOD,
                 SUBSCRIPTION_ON_HOLD,
                 SUBSCRIPTION_PAUSED,
                 SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED,
                 SUBSCRIPTION_EXPIRED -> {
                entitlementSyncService.syncPurchaseTokenFromRtdn(
                        purchaseToken,
                        eventTime
                );

                log.info(
                        "rtdn_subscription_sync_requested messageId={} type={} tokenHash={}",
                        messageId,
                        notificationType,
                        tokenHash
                );
            }

            case SUBSCRIPTION_REVOKED,
                 SUBSCRIPTION_PENDING_PURCHASE_CANCELED -> {
                /*
                 * revoke / pending cancelled 可以先立即關閉已綁定 token。
                 * 若 token 是新 token 且尚未綁定，syncPurchaseTokenFromRtdn 仍有機會透過 linkedPurchaseToken 找回 user。
                 */
                entitlementSyncService.closeByPurchaseTokenHash(
                        tokenHash,
                        "REVOKED",
                        eventTime
                );

                entitlementSyncService.syncPurchaseTokenFromRtdn(
                        purchaseToken,
                        eventTime
                );

                referralBillingBridgeService.markRefundedOrRevoked(
                        tokenHash,
                        false,
                        eventTime
                );

                log.info(
                        "rtdn_subscription_closed messageId={} type={} tokenHash={}",
                        messageId,
                        notificationType,
                        tokenHash
                );
            }

            default -> log.info(
                    "rtdn_subscription_unknown_type messageId={} type={}",
                    messageId,
                    notificationType
            );
        }
    }

    private void handleVoidedPurchaseNotification(
            JsonNode voided,
            Instant eventTime,
            String messageId
    ) {
        String purchaseToken = voided.path("purchaseToken").asText(null);

        if (purchaseToken == null || purchaseToken.isBlank()) {
            log.warn("rtdn_voided_missing_purchase_token messageId={}", messageId);
            return;
        }

        String tokenHash = EntitlementSyncService.sha256Hex(purchaseToken);

        entitlementSyncService.closeByPurchaseTokenHash(
                tokenHash,
                "REVOKED",
                eventTime
        );

        /*
         * 如果 voided token 是新 token，仍嘗試透過 linkedPurchaseToken 回查 user。
         */
        entitlementSyncService.syncPurchaseTokenFromRtdn(
                purchaseToken,
                eventTime
        );

        referralBillingBridgeService.markRefundedOrRevoked(
                tokenHash,
                false,
                eventTime
        );

        log.info(
                "rtdn_voided_purchase_closed messageId={} tokenHash={}",
                messageId,
                tokenHash
        );
    }

    private Instant resolveEventTime(JsonNode root) {
        String millisText = root.path("eventTimeMillis").asText(null);
        if (millisText == null || millisText.isBlank()) {
            return Instant.now();
        }

        try {
            return Instant.ofEpochMilli(Long.parseLong(millisText));
        } catch (Exception ignored) {
            return Instant.now();
        }
    }
}
