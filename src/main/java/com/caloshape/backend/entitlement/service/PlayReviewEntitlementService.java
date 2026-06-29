package com.caloshape.backend.entitlement.service;

import com.caloshape.backend.entitlement.entity.UserEntitlementEntity;
import com.caloshape.backend.entitlement.repo.UserEntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class PlayReviewEntitlementService {

    static final String SOURCE = "INTERNAL";
    static final String PRODUCT_ID = "google_play_review";

    private final UserEntitlementRepository entitlements;

    public PlayReviewEntitlementService(UserEntitlementRepository entitlements) {
        this.entitlements = entitlements;
    }

    @Transactional
    public void ensureActive(Long userId, Instant now, Duration validity) {
        UserEntitlementEntity entitlement = entitlements
                .findTopByUserIdAndSourceAndProductIdOrderByValidToUtcDesc(userId, SOURCE, PRODUCT_ID)
                .orElseGet(UserEntitlementEntity::new);

        entitlement.setUserId(userId);
        entitlement.setEntitlementType("YEARLY");
        entitlement.setStatus("ACTIVE");
        if (entitlement.getValidFromUtc() == null || entitlement.getValidFromUtc().isAfter(now)) {
            entitlement.setValidFromUtc(now);
        }

        Instant requestedValidTo = now.plus(validity);
        if (entitlement.getValidToUtc() == null || entitlement.getValidToUtc().isBefore(requestedValidTo)) {
            entitlement.setValidToUtc(requestedValidTo);
        }

        entitlement.setSource(SOURCE);
        entitlement.setProductId(PRODUCT_ID);
        entitlement.setSubscriptionState("INTERNAL_REVIEW");
        entitlement.setPaymentState("OK");
        entitlement.setLastVerifiedAtUtc(now);
        entitlement.setUpdatedAtUtc(now);
        if (entitlement.getCreatedAtUtc() == null) {
            entitlement.setCreatedAtUtc(now);
        }

        entitlements.save(entitlement);
    }
}
