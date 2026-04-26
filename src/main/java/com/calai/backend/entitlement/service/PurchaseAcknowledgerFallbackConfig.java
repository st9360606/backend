package com.calai.backend.entitlement.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PurchaseAcknowledgerFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(PurchaseAcknowledger.class)
    public PurchaseAcknowledger noopPurchaseAcknowledger() {
        return (productId, purchaseToken, acknowledgementState) -> false;
    }
}