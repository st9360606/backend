package com.calai.backend.referral.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "membership_reward_ledger",
        indexes = {
                @Index(name = "idx_membership_reward_user_granted", columnList = "user_id,granted_at_utc")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_membership_reward_source_ref", columnNames = {"source_type", "source_ref_id"})
        })
public class MembershipRewardLedgerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_ref_id", nullable = false)
    private Long sourceRefId;

    @Column(name = "grant_status", nullable = false, length = 16)
    private String grantStatus;

    @Column(name = "days_added", nullable = false)
    private Integer daysAdded;

    @Column(name = "old_premium_until")
    private Instant oldPremiumUntil;

    @Column(name = "new_premium_until")
    private Instant newPremiumUntil;

    @Column(name = "granted_at_utc", nullable = false)
    private Instant grantedAtUtc;
}
