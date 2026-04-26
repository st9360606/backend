package com.calai.backend.entitlement.service;

public interface PurchaseAcknowledger {

    boolean acknowledgeWithRetry(
            String productId,
            String purchaseToken,
            String acknowledgementState
    );
}
