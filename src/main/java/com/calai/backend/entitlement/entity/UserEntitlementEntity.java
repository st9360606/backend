package com.calai.backend.entitlement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "user_entitlements",
        indexes = @Index(name = "idx_entitlements_user", columnList = "user_id,status,valid_to_utc")
)
public class UserEntitlementEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "entitlement_type", length = 16, nullable = false)
    private String entitlementType; // TRIAL/MONTHLY/YEARLY

    @Column(length = 16, nullable = false)
    private String status; // ACTIVE/EXPIRED/CANCELLED

    @Column(name = "valid_from_utc", nullable = false)
    private Instant validFromUtc;

    @Column(name = "valid_to_utc", nullable = false)
    private Instant validToUtc;

    @Column(name = "purchase_token_hash", length = 64)
    private String purchaseTokenHash;

    @Column(name = "last_verified_at_utc")
    private Instant lastVerifiedAtUtc;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}
