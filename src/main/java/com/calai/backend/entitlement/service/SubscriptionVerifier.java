package com.calai.backend.entitlement.service;

import java.time.Instant;

public interface SubscriptionVerifier {

    record VerifiedSubscription(
            boolean active,
            String productId,
            Instant expiryTimeUtc,
            boolean freeTrial,
            String subscriptionState,
            String acknowledgementState,
            boolean autoRenewEnabled,
            String offerPhase,
            String latestOrderId,
            String linkedPurchaseToken,
            boolean testPurchase,
            boolean pending
    ) {}

    /**
     * 驗證 purchaseToken 是否有效。
     * productId / expiry / offer phase / state 一律以 Google Play Developer API 回傳為準。
     */
    VerifiedSubscription verify(String purchaseToken) throws Exception;
}
