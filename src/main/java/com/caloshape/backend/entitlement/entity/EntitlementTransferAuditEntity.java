package com.caloshape.backend.entitlement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "entitlement_transfer_audit",
        indexes = {
                @Index(name = "idx_entitlement_transfer_token", columnList = "purchase_token_hash"),
                @Index(name = "idx_entitlement_transfer_old_user", columnList = "old_user_id"),
                @Index(name = "idx_entitlement_transfer_new_user", columnList = "new_user_id")
        }
)
public class EntitlementTransferAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_token_hash", length = 64, nullable = false)
    private String purchaseTokenHash;

    @Column(name = "old_user_id", nullable = false)
    private Long oldUserId;

    @Column(name = "new_user_id", nullable = false)
    private Long newUserId;

    @Column(name = "reason", length = 64, nullable = false)
    private String reason;

    @Column(name = "product_id", length = 128)
    private String productId;

    @Column(name = "entitlement_type", length = 16)
    private String entitlementType;

    @Column(name = "google_subscription_state", length = 64)
    private String googleSubscriptionState;

    @Column(name = "valid_to_utc")
    private Instant validToUtc;

    @Column(name = "transferred_at_utc", nullable = false)
    private Instant transferredAtUtc;

    @PrePersist
    void prePersist() {
        if (transferredAtUtc == null) {
            transferredAtUtc = Instant.now();
        }
    }
}
