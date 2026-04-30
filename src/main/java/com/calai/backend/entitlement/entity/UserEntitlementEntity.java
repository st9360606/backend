package com.calai.backend.entitlement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "user_entitlements",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_entitlements_purchase_token_hash",
                        columnNames = "purchase_token_hash"
                )
        },
        indexes = {
                @Index(
                        name = "idx_entitlements_user",
                        columnList = "user_id,status,valid_to_utc"
                ),
                @Index(
                        name = "idx_entitlements_linked_purchase_token_hash",
                        columnList = "linked_purchase_token_hash"
                ),
                @Index(
                        name = "idx_entitlements_source_state",
                        columnList = "source,subscription_state"
                ),
                @Index(
                        name = "idx_entitlements_reverify",
                        columnList = "source,status,last_google_verified_at_utc"
                ),
                @Index(
                        name = "idx_entitlements_payment_state",
                        columnList = "payment_state,status,valid_to_utc"
                )
        }
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
    private String status; // ACTIVE/EXPIRED/CANCELLED/REVOKED

    @Column(name = "valid_from_utc", nullable = false)
    private Instant validFromUtc;

    @Column(name = "valid_to_utc", nullable = false)
    private Instant validToUtc;

    @Column(name = "purchase_token_hash", length = 64)
    private String purchaseTokenHash;

    /**
     * Encrypted raw Google Play purchase token.
     * Required for backend-side periodic re-verification when RTDN is delayed/missed.
     */
    @Column(name = "purchase_token_ciphertext", length = 2048)
    private String purchaseTokenCiphertext;

    @Column(name = "last_verified_at_utc")
    private Instant lastVerifiedAtUtc;

    @Column(name = "last_google_verified_at_utc")
    private Instant lastGoogleVerifiedAtUtc;

    /** GOOGLE_PLAY / INTERNAL / DEV */
    @Column(name = "source", length = 32, nullable = false)
    private String source = "INTERNAL";

    @Column(name = "product_id", length = 128)
    private String productId;

    @Column(name = "subscription_state", length = 64)
    private String subscriptionState;

    /** OK / GRACE / ON_HOLD / EXPIRED / REVOKED / PENDING_PURCHASE_CANCELED / UNKNOWN */
    @Column(name = "payment_state", length = 32)
    private String paymentState;

    /** Future enhancement: fill from Google Play line item/state context when available. */
    @Column(name = "grace_until_utc")
    private Instant graceUntilUtc;

    /** EXPIRED_BY_TIME / GOOGLE_PLAY_ON_HOLD / GOOGLE_PLAY_REVOKED / SUPERSEDED / etc. */
    @Column(name = "close_reason", length = 64)
    private String closeReason;

    /** FREE_TRIAL / INTRODUCTORY / BASE / PRORATION / UNKNOWN */
    @Column(name = "offer_phase", length = 32)
    private String offerPhase;

    @Column(name = "auto_renew_enabled")
    private Boolean autoRenewEnabled;

    @Column(name = "acknowledgement_state", length = 64)
    private String acknowledgementState;

    @Column(name = "latest_order_id", length = 128)
    private String latestOrderId;

    @Column(name = "linked_purchase_token_hash", length = 64)
    private String linkedPurchaseTokenHash;

    @Column(name = "last_rtdn_at_utc")
    private Instant lastRtdnAtUtc;

    @Column(name = "revoked_at_utc")
    private Instant revokedAtUtc;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (source == null || source.isBlank()) source = "INTERNAL";

        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}
