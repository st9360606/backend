-- Referral / reward / inbox / email outbox
CREATE TABLE IF NOT EXISTS user_referral_codes
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    promo_code     VARCHAR(24)  NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at_utc DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_user_referral_codes_user (user_id),
    UNIQUE KEY ux_user_referral_codes_code (promo_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS referral_claims
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    inviter_user_id           BIGINT       NOT NULL,
    invitee_user_id           BIGINT       NOT NULL,
    promo_code                VARCHAR(24)  NOT NULL,
    status                    VARCHAR(32)  NOT NULL,
    reject_reason             VARCHAR(64)  NOT NULL DEFAULT 'NONE',
    subscribed_at_utc         DATETIME(6)  NULL,
    qualified_at_utc          DATETIME(6)  NULL,
    verification_deadline_utc DATETIME(6)  NULL,
    rewarded_at_utc           DATETIME(6)  NULL,
    refund_detected_at_utc    DATETIME(6)  NULL,
    auto_renew_status         VARCHAR(16)  NULL,
    purchase_token_hash       CHAR(64)     NULL,
    risk_score                INT          NULL,
    risk_decision             VARCHAR(16)  NULL,
    created_at_utc            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_referral_claims_invitee (invitee_user_id),
    UNIQUE KEY ux_referral_claims_purchase_token (purchase_token_hash),
    INDEX idx_referral_claims_inviter_status (inviter_user_id, status, verification_deadline_utc),
    INDEX idx_referral_claims_status_deadline (status, verification_deadline_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS membership_reward_ledger
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    source_type        VARCHAR(32)  NOT NULL,
    source_ref_id      BIGINT       NOT NULL,
    grant_status       VARCHAR(16)  NOT NULL,
    days_added         INT          NOT NULL,
    old_premium_until  DATETIME(6)  NULL,
    new_premium_until  DATETIME(6)  NULL,
    granted_at_utc     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_membership_reward_source_ref (source_type, source_ref_id),
    INDEX idx_membership_reward_user_granted (user_id, granted_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS referral_risk_signals
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    claim_id                 BIGINT       NOT NULL,
    device_hash              CHAR(64)     NULL,
    ip_hash                  CHAR(64)     NULL,
    payment_fingerprint_hash CHAR(64)     NULL,
    risk_score               INT          NOT NULL,
    risk_flags_json          JSON         NULL,
    decision                 VARCHAR(16)  NOT NULL,
    created_at_utc           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_referral_risk_claim (claim_id, created_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS referral_case_snapshot
(
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    inviter_user_id            BIGINT       NOT NULL,
    total_invited              INT          NOT NULL DEFAULT 0,
    success_count              INT          NOT NULL DEFAULT 0,
    rejected_count             INT          NOT NULL DEFAULT 0,
    pending_verification_count INT          NOT NULL DEFAULT 0,
    total_rewarded_days        INT          NOT NULL DEFAULT 0,
    current_premium_until      DATETIME(6)  NULL,
    updated_at_utc             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_referral_case_snapshot_user (inviter_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_notifications
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    title          VARCHAR(120) NOT NULL,
    message        VARCHAR(500) NOT NULL,
    deep_link      VARCHAR(128) NULL,
    source_type    VARCHAR(32)  NOT NULL,
    source_ref_id  BIGINT       NOT NULL,
    is_read        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at_utc DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_user_notifications_source (user_id, source_type, source_ref_id),
    INDEX idx_user_notifications_user_created (user_id, created_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS email_outbox
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NULL,
    to_email              VARCHAR(320) NOT NULL,
    template_type         VARCHAR(32)  NOT NULL,
    template_payload_json JSON         NOT NULL,
    dedupe_key            VARCHAR(100) NOT NULL,
    retry_count           INT          NOT NULL DEFAULT 0,
    status                VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at_utc        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at_utc           DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_email_outbox_dedupe (dedupe_key),
    INDEX idx_email_outbox_status_created (status, created_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
