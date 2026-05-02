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
                @Index(name = "idx_membership_reward_user_granted", columnList = "user_id,granted_at_utc"),
                @Index(name = "idx_membership_reward_source_status", columnList = "source_type,source_ref_id,grant_status"),
                @Index(name = "idx_membership_reward_google_token", columnList = "google_purchase_token_hash"),
                @Index(name = "idx_membership_reward_channel_status", columnList = "reward_channel,google_defer_status"),
                @Index(name = "idx_membership_reward_trace", columnList = "trace_id")
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

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "grant_status", nullable = false, length = 32)
    private String grantStatus;

    @Column(name = "reward_channel", length = 32)
    private String rewardChannel;

    @Column(name = "google_purchase_token_hash", length = 64)
    private String googlePurchaseTokenHash;

    @Column(name = "google_defer_status", length = 32)
    private String googleDeferStatus;

    @Column(name = "google_defer_request_json", columnDefinition = "longtext")
    private String googleDeferRequestJson;

    @Column(name = "google_defer_response_json", columnDefinition = "longtext")
    private String googleDeferResponseJson;

    @Column(name = "google_defer_http_status")
    private Integer googleDeferHttpStatus;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "days_added", nullable = false)
    private Integer daysAdded;

    @Column(name = "old_premium_until")
    private Instant oldPremiumUntil;

    @Column(name = "new_premium_until")
    private Instant newPremiumUntil;

    @Column(name = "next_retry_at_utc")
    private Instant nextRetryAtUtc;

    @Column(name = "granted_at_utc", nullable = false)
    private Instant grantedAtUtc;
}
