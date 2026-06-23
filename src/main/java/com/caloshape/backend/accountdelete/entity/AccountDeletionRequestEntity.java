package com.caloshape.backend.accountdelete.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter @Setter
@Entity
@Table(name = "account_deletion_requests",
        indexes = @Index(name="idx_account_del_status", columnList = "req_status,next_retry_at_utc"),
        uniqueConstraints = @UniqueConstraint(name="uk_account_del_user", columnNames = "user_id"))
public class AccountDeletionRequestEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name="user_id", nullable = false)
    private Long userId;

    @Column(name="req_status", length = 16, nullable = false)
    private String reqStatus; // REQUESTED/RUNNING/DONE/FAILED

    @Column(name="requested_at_utc", nullable = false)
    private Instant requestedAtUtc;

    @Column(name="started_at_utc")
    private Instant startedAtUtc;

    @Column(name="completed_at_utc")
    private Instant completedAtUtc;

    @Column(name = "subscription_warning_acknowledged", nullable = false)
    private boolean subscriptionWarningAcknowledged = false;

    @Column(name = "user_requested_google_play_cancel", nullable = false)
    private boolean userRequestedGooglePlayCancel = false;

    @Column(name = "has_active_google_play_subscription_at_request", nullable = false)
    private boolean hasActiveGooglePlaySubscriptionAtRequest = false;

    @Column(name = "active_entitlement_type_at_request", length = 16)
    private String activeEntitlementTypeAtRequest;

    @Column(name = "active_entitlement_source_at_request", length = 32)
    private String activeEntitlementSourceAtRequest;

    @Column(name = "active_product_id_at_request", length = 128)
    private String activeProductIdAtRequest;

    @Column(name = "active_valid_to_utc_at_request")
    private Instant activeValidToUtcAtRequest;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name="next_retry_at_utc")
    private Instant nextRetryAtUtc;

    @Column(name="last_error", columnDefinition = "TEXT")
    private String lastError;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (requestedAtUtc == null) requestedAtUtc = Instant.now();
        if (reqStatus == null || reqStatus.isBlank()) reqStatus = "REQUESTED";
    }
}
