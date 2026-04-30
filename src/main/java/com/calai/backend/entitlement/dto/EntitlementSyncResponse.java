package com.calai.backend.entitlement.dto;

import java.time.Instant;

public record EntitlementSyncResponse(
        String status,              // ACTIVE / INACTIVE
        String entitlementType,     // TRIAL / MONTHLY / YEARLY / null
        String premiumStatus,       // TRIAL / PREMIUM / FREE
        Instant currentPremiumUntil,
        Instant trialEndsAt,
        Integer trialDaysLeft,
        Boolean paymentIssue
) {}
