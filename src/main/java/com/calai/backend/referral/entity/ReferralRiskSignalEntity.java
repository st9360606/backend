package com.calai.backend.referral.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "referral_risk_signals",
        indexes = @Index(name = "idx_referral_risk_claim", columnList = "claim_id,created_at_utc"))
public class ReferralRiskSignalEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "device_hash", length = 64)
    private String deviceHash;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "payment_fingerprint_hash", length = 64)
    private String paymentFingerprintHash;

    @Column(name = "risk_score", nullable = false)
    private Integer riskScore;

    @Column(name = "risk_flags_json", columnDefinition = "json")
    private String riskFlagsJson;

    @Column(name = "decision", nullable = false, length = 16)
    private String decision;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @PrePersist
    void prePersist() {
        if (createdAtUtc == null) createdAtUtc = Instant.now();
    }
}
