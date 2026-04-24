package com.calai.backend.referral.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "referral_claims",
        indexes = {
                @Index(name = "idx_referral_claims_inviter_status", columnList = "inviter_user_id,status,verification_deadline_utc"),
                @Index(name = "idx_referral_claims_status_deadline", columnList = "status,verification_deadline_utc")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_referral_claims_invitee", columnNames = "invitee_user_id"),
                @UniqueConstraint(name = "ux_referral_claims_purchase_token", columnNames = "purchase_token_hash")
        })
public class ReferralClaimEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inviter_user_id", nullable = false)
    private Long inviterUserId;

    @Column(name = "invitee_user_id", nullable = false)
    private Long inviteeUserId;

    @Column(name = "promo_code", nullable = false, length = 24)
    private String promoCode;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "reject_reason", nullable = false, length = 64)
    private String rejectReason = "NONE";

    @Column(name = "subscribed_at_utc")
    private Instant subscribedAtUtc;

    @Column(name = "qualified_at_utc")
    private Instant qualifiedAtUtc;

    @Column(name = "verification_deadline_utc")
    private Instant verificationDeadlineUtc;

    @Column(name = "rewarded_at_utc")
    private Instant rewardedAtUtc;

    @Column(name = "refund_detected_at_utc")
    private Instant refundDetectedAtUtc;

    @Column(name = "auto_renew_status", length = 16)
    private String autoRenewStatus;

    @Column(name = "purchase_token_hash", length = 64)
    private String purchaseTokenHash;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "risk_decision", length = 16)
    private String riskDecision;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}
