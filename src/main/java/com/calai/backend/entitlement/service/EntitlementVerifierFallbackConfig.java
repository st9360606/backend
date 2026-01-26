package com.calai.backend.entitlement.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntitlementVerifierFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(SubscriptionVerifier.class)
    public SubscriptionVerifier noopSubscriptionVerifier() {
        return purchaseToken -> new SubscriptionVerifier.VerifiedSubscription(false, null, null);
    }
}
