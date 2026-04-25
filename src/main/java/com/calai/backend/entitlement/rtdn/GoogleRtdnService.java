package com.calai.backend.entitlement.rtdn;

import com.calai.backend.referral.service.ReferralBillingBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@RequiredArgsConstructor
@Slf4j
@Service
public class GoogleRtdnService {

    private static final int SUBSCRIPTION_REVOKED = 12;
    private static final int SUBSCRIPTION_PENDING_PURCHASE_CANCELED = 20;

    private final ObjectMapper objectMapper;
    private final ReferralBillingBridgeService referralBillingBridgeService;

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
                handleSubscriptionNotification(root.path("subscriptionNotification"), eventTime, messageId);
                return;
            }

            if (root.hasNonNull("voidedPurchaseNotification")) {
                handleVoidedPurchaseNotification(root.path("voidedPurchaseNotification"), eventTime, messageId);
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
            log.warn("rtdn_subscription_missing_purchase_token messageId={} type={}", messageId, notificationType);
            return;
        }

        String tokenHash = sha256Hex(purchaseToken);

        switch (notificationType) {
            case SUBSCRIPTION_REVOKED, SUBSCRIPTION_PENDING_PURCHASE_CANCELED -> {
                referralBillingBridgeService.markRefundedOrRevoked(
                        tokenHash,
                        false,
                        eventTime
                );
                log.info("rtdn_subscription_rejected messageId={} type={} tokenHash={}",
                        messageId, notificationType, tokenHash);
            }

            default -> log.info("rtdn_subscription_no_referral_action messageId={} type={}",
                    messageId, notificationType);
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

        String tokenHash = sha256Hex(purchaseToken);

        referralBillingBridgeService.markRefundedOrRevoked(
                tokenHash,
                false,
                eventTime
        );

        log.info("rtdn_voided_purchase_rejected messageId={} tokenHash={}", messageId, tokenHash);
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

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA256_FAILED", e);
        }
    }
}
