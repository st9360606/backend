package com.calai.backend.entitlement.service;

import com.google.api.client.util.GenericData;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseLineItem;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlaySubscriptionVerifier implements SubscriptionVerifier {

    private final AndroidPublisher publisher;
    private final GooglePlayVerifierProperties props;

    @Override
    public VerifiedSubscription verify(String purchaseToken) throws Exception {
        if (props.isDevFakeTokensEnabled() && isDevFakePurchaseToken(purchaseToken)) {
            return verifyDevFakeSubscription(purchaseToken);
        }

        SubscriptionPurchaseV2 v2 = publisher.purchases()
                .subscriptionsv2()
                .get(props.getPackageName(), purchaseToken)
                .execute();

        if (v2 == null) {
            return inactive(null, null, null, null, false, false);
        }

        List<SubscriptionPurchaseLineItem> items = v2.getLineItems();
        if (items == null || items.isEmpty()) {
            return inactive(
                    v2.getSubscriptionState(),
                    v2.getAcknowledgementState(),
                    v2.getLatestOrderId(),
                    v2.getLinkedPurchaseToken(),
                    v2.getTestPurchase() != null,
                    isPendingState(v2.getSubscriptionState())
            );
        }

        SubscriptionPurchaseLineItem best = items.stream()
                .filter(it -> it.getExpiryTime() != null && it.getProductId() != null)
                .max(Comparator.comparing(it -> Instant.parse(it.getExpiryTime())))
                .orElse(null);

        if (best == null) {
            return inactive(
                    v2.getSubscriptionState(),
                    v2.getAcknowledgementState(),
                    v2.getLatestOrderId(),
                    v2.getLinkedPurchaseToken(),
                    v2.getTestPurchase() != null,
                    isPendingState(v2.getSubscriptionState())
            );
        }

        Instant expiry = Instant.parse(best.getExpiryTime());
        String state = v2.getSubscriptionState();
        boolean notExpired = expiry.isAfter(Instant.now());
        boolean active = notExpired && isEntitledState(state);

        String offerPhaseCode = resolveOfferPhaseCode(best);
        boolean freeTrial = "FREE_TRIAL".equals(offerPhaseCode);

        boolean autoRenewEnabled = best.getAutoRenewingPlan() != null
                && Boolean.TRUE.equals(best.getAutoRenewingPlan().getAutoRenewEnabled());

        return new VerifiedSubscription(
                active,
                best.getProductId(),
                expiry,
                freeTrial,
                state,
                v2.getAcknowledgementState(),
                autoRenewEnabled,
                offerPhaseCode,
                v2.getLatestOrderId(),
                v2.getLinkedPurchaseToken(),
                v2.getTestPurchase() != null,
                isPendingState(state)
        );
    }

    private static final String DEV_FAKE_TOKEN_PREFIX = "fake-dev-sub::";

    private static boolean isDevFakePurchaseToken(String purchaseToken) {
        return purchaseToken != null && purchaseToken.startsWith(DEV_FAKE_TOKEN_PREFIX);
    }

    private static VerifiedSubscription verifyDevFakeSubscription(String purchaseToken) {
        String[] parts = purchaseToken.split("::");

        if (parts.length < 4) {
            return inactive(
                    "DEV_FAKE_TOKEN_INVALID",
                    "ACKNOWLEDGEMENT_STATE_UNSPECIFIED",
                    null,
                    null,
                    true,
                    false
            );
        }

        String productId = parts[1];
        String phase = parts[2];

        boolean freeTrial = "trial".equalsIgnoreCase(phase);
        boolean yearly = productId != null && productId.toLowerCase().contains("yearly");

        Instant expiry = Instant.now().plusSeconds(
                freeTrial ? 3L * 86_400L : yearly ? 365L * 86_400L : 30L * 86_400L
        );

        return new VerifiedSubscription(
                true,
                productId,
                expiry,
                freeTrial,
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                true,
                freeTrial ? "FREE_TRIAL" : "BASE",
                "DEV-FAKE-ORDER-" + System.currentTimeMillis(),
                null,
                true,
                false
        );
    }

    private static VerifiedSubscription inactive(
            String subscriptionState,
            String acknowledgementState,
            String latestOrderId,
            String linkedPurchaseToken,
            boolean testPurchase,
            boolean pending
    ) {
        return new VerifiedSubscription(
                false,
                null,
                null,
                false,
                subscriptionState,
                acknowledgementState,
                false,
                "UNKNOWN",
                latestOrderId,
                linkedPurchaseToken,
                testPurchase,
                pending
        );
    }

    private static boolean isEntitledState(String state) {
        return "SUBSCRIPTION_STATE_ACTIVE".equals(state)
                || "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(state)
                || "SUBSCRIPTION_STATE_CANCELED".equals(state);
    }

    private static boolean isPendingState(String state) {
        return "SUBSCRIPTION_STATE_PENDING".equals(state)
                || "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED".equals(state);
    }

    /**
     * Google REST API 有 lineItems[].offerPhase。
     *
     * 但目前 google-api-services-androidpublisher 的 Java model
     * 沒有產生 typed OfferPhase class / getOfferPhase()。
     *
     * 所以這裡用 GenericJson / GenericData 的 raw field 方式讀：
     *
     * offerPhase.freeTrial
     * offerPhase.introductoryPrice
     * offerPhase.basePrice
     * offerPhase.prorationPeriod
     */
    private static String resolveOfferPhaseCode(SubscriptionPurchaseLineItem item) {
        Object phase = readDynamicField(item, "offerPhase");

        if (phase == null) {
            return "UNKNOWN";
        }

        if (hasDynamicField(phase, "freeTrial")) {
            return "FREE_TRIAL";
        }

        if (hasDynamicField(phase, "introductoryPrice")) {
            return "INTRODUCTORY";
        }

        if (hasDynamicField(phase, "basePrice")) {
            return "BASE";
        }

        if (hasDynamicField(phase, "prorationPeriod")) {
            return "PRORATION";
        }

        return "UNKNOWN";
    }

    private static boolean hasDynamicField(Object source, String fieldName) {
        return readDynamicField(source, fieldName) != null;
    }

    /**
     * 支援兩種來源：
     *
     * 1. GenericData / GenericJson
     * 2. Map
     *
     * Google API Java client 對新欄位常會先放在 unknownKeys，
     * 所以要同時檢查 data.get(fieldName) 與 data.getUnknownKeys().
     */
    @SuppressWarnings("unchecked")
    private static Object readDynamicField(Object source, String fieldName) {
        if (source == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        if (source instanceof GenericData data) {
            Object value = data.get(fieldName);
            if (value != null) {
                return value;
            }

            Map<String, Object> unknownKeys = data.getUnknownKeys();
            if (unknownKeys != null) {
                value = unknownKeys.get(fieldName);
                if (value != null) {
                    return value;
                }
            }
        }

        if (source instanceof Map<?, ?> map) {
            return map.get(fieldName);
        }

        return null;
    }
}
