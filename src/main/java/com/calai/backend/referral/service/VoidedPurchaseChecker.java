package com.calai.backend.referral.service;

import java.time.Instant;

public interface VoidedPurchaseChecker {

    boolean isVoidedSubscriptionPurchase(
            String purchaseToken,
            Instant fromUtc,
            Instant toUtc
    );
}
