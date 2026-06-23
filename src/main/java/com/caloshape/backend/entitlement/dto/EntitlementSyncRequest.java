package com.caloshape.backend.entitlement.dto;

import java.util.List;

public record EntitlementSyncRequest(
        List<PurchaseTokenPayload> purchases
) {
    public record PurchaseTokenPayload(
            String productId,
            String purchaseToken
    ) {}
}
