package com.calai.backend.entitlement.dto;

public record EntitlementSyncResponse(
        String status,           // ACTIVE / INACTIVE
        String entitlementType   // MONTHLY / YEARLY / null
) {}
