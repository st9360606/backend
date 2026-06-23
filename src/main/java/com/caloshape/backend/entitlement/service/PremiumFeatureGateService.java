package com.caloshape.backend.entitlement.service;

import com.caloshape.backend.foodlog.web.error.SubscriptionRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class PremiumFeatureGateService {

    private final EntitlementService entitlementService;
    private final Clock clock;

    public void requirePremium(Long userId) {
        if (!entitlementService.hasActiveEntitlement(userId, clock.instant())) {
            throw new SubscriptionRequiredException("SUBSCRIPTION_REQUIRED");
        }
    }
}
