package com.calai.backend.entitlement.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntitlementVerifierFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(SubscriptionVerifier.class)
    public SubscriptionVerifier noopSubscriptionVerifier() {
        return purchaseToken -> new SubscriptionVerifier.VerifiedSubscription(
                false,      // active
                null,       // productId
                null,       // expiryTimeUtc
                false,      // freeTrial
                null,       // subscriptionState
                null,       // acknowledgementState
                false,      // autoRenewEnabled
                "UNKNOWN",  // offerPhase
                null,       // latestOrderId
                null,       // linkedPurchaseToken
                false,      // testPurchase
                false       // pending
        );
    }
}
