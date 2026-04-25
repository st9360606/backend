package com.calai.backend.entitlement.trial;

import java.time.Instant;

public record TrialGrantResponse(
        boolean ok,
        String premiumStatus, // TRIAL / PREMIUM
        String tier,          // TRIAL / MONTHLY / YEARLY
        Instant validToUtc
) {}
