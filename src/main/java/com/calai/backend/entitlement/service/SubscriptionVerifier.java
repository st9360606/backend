package com.calai.backend.entitlement.service;

import java.time.Instant;

public interface SubscriptionVerifier {

    record VerifiedSubscription(
            boolean active,
            String productId,
            Instant expiryTimeUtc
    ) {}

    /**
     * 驗證 purchaseToken 是否有效（仍在有效期）
     * - productId 只是「輔助」，真正 productId 以 Google 回傳為準
     */
    VerifiedSubscription verify(String purchaseToken) throws Exception;
}
