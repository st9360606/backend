-- ============================================================
-- Table: referral_claims
-- Purpose:
-- 1. Store referral claim lifecycle records.
-- 2. Track invitee subscription qualification and cooldown period.
-- 3. Support anti-abuse/risk review fields.
-- 4. Support reward processing and refund detection.
-- ============================================================

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

    -- Legacy verification deadline kept for backward compatibility during rollout.
    verification_deadline_utc DATETIME(6)  NULL,

    -- Commercial name for the 7-day verification/cooldown deadline.
    cooldown_until_utc        DATETIME(6)  NULL,

    rewarded_at_utc           DATETIME(6)  NULL,
    refund_detected_at_utc    DATETIME(6)  NULL,

    auto_renew_status         VARCHAR(16)  NULL,
    purchase_token_hash       CHAR(64)     NULL,

    risk_score                INT          NULL,
    risk_decision             VARCHAR(16)  NULL,

    created_at_utc            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY ux_referral_claims_invitee
        (invitee_user_id),

    UNIQUE KEY ux_referral_claims_purchase_token
        (purchase_token_hash),

    INDEX idx_referral_claims_inviter_status
        (inviter_user_id, status, verification_deadline_utc),

    INDEX idx_referral_claims_status_deadline
        (status, verification_deadline_utc),

    INDEX idx_referral_claims_status_cooldown
        (status, cooldown_until_utc),

    INDEX idx_referral_claims_status_updated
        (status, updated_at_utc)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
