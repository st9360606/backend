package com.calai.backend.referral.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "referral_case_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "ux_referral_case_snapshot_user", columnNames = "inviter_user_id"))
public class ReferralCaseSnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inviter_user_id", nullable = false)
    private Long inviterUserId;

    @Column(name = "total_invited", nullable = false)
    private Integer totalInvited = 0;

    @Column(name = "success_count", nullable = false)
    private Integer successCount = 0;

    @Column(name = "rejected_count", nullable = false)
    private Integer rejectedCount = 0;

    @Column(name = "pending_verification_count", nullable = false)
    private Integer pendingVerificationCount = 0;

    @Column(name = "total_rewarded_days", nullable = false)
    private Integer totalRewardedDays = 0;

    @Column(name = "current_premium_until")
    private Instant currentPremiumUntil;

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
