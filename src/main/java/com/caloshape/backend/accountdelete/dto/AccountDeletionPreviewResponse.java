package com.caloshape.backend.accountdelete.dto;

import java.time.Instant;

public record AccountDeletionPreviewResponse(
        boolean canDelete,
        boolean hasActiveGooglePlaySubscription,
        String premiumStatus,
        String entitlementType,
        Instant currentPremiumUntil,
        String subscriptionManagementUrl,
        boolean requiresSubscriptionWarning
) {
}
