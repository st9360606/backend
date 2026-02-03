package com.calai.backend.foodlog.quota.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user_ai_quota_state")
public class UserAiQuotaStateEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "daily_key", length = 32, nullable = false)
    private String dailyKey;

    @Column(name = "daily_count", nullable = false)
    private int dailyCount;

    @Column(name = "monthly_key", length = 32, nullable = false)
    private String monthlyKey;

    @Column(name = "monthly_count", nullable = false)
    private int monthlyCount;

    @Column(name = "cooldown_strikes", nullable = false)
    private int cooldownStrikes;

    @Column(name = "next_allowed_at_utc")
    private Instant nextAllowedAtUtc;

    @Column(name = "force_low_until_utc")
    private Instant forceLowUntilUtc;

    @Column(name = "cooldown_reason", length = 16)
    private String cooldownReason;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        if (updatedAtUtc == null) updatedAtUtc = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}