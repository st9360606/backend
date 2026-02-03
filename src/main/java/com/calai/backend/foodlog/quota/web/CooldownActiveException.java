package com.calai.backend.foodlog.quota.web;

import com.calai.backend.foodlog.quota.model.CooldownReason;
import java.time.Instant;

public class CooldownActiveException extends RuntimeException {

    private final Instant nextAllowedAtUtc;
    private final int cooldownSeconds;
    private final int cooldownLevel;
    private final CooldownReason cooldownReason;

    public CooldownActiveException(
            String message,
            Instant nextAllowedAtUtc,
            int cooldownSeconds,
            int cooldownLevel,
            CooldownReason cooldownReason
    ) {
        super(message);
        this.nextAllowedAtUtc = nextAllowedAtUtc;
        this.cooldownSeconds = cooldownSeconds;
        this.cooldownLevel = cooldownLevel;
        this.cooldownReason = cooldownReason;
    }

    public Instant nextAllowedAtUtc() { return nextAllowedAtUtc; }
    public int cooldownSeconds() { return cooldownSeconds; }
    public int cooldownLevel() { return cooldownLevel; }
    public CooldownReason cooldownReason() { return cooldownReason; }
}