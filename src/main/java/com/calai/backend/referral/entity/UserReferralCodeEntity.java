package com.calai.backend.referral.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user_referral_codes",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_user_referral_codes_user", columnNames = "user_id"),
                @UniqueConstraint(name = "ux_user_referral_codes_code", columnNames = "promo_code")
        })
public class UserReferralCodeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "promo_code", nullable = false, length = 24)
    private String promoCode;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

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
