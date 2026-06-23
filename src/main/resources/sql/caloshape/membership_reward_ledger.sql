-- ============================================================
-- Table: membership_reward_ledger
-- Purpose:
-- 1. Store append-only reward grant/defer attempts.
-- 2. Preserve Google Play defer request/response/http status for CS.
-- 3. Allow retry attempts without overwriting previous attempts.
-- 4. Support referral reward traceability and commercial audit.
-- ============================================================

CREATE TABLE IF NOT EXISTS membership_reward_ledger
(
    id                         BIGINT       NOT NULL AUTO_INCREMENT,

    user_id                    BIGINT       NOT NULL,

    source_type                VARCHAR(32)  NOT NULL,
    source_ref_id              BIGINT       NOT NULL,

    attempt_no                 INT          NOT NULL DEFAULT 1,
    trace_id                   VARCHAR(64)  NULL,

    grant_status               VARCHAR(32)  NOT NULL,
    reward_channel             VARCHAR(32)  NULL,

    google_purchase_token_hash CHAR(64)     NULL,
    google_defer_status        VARCHAR(32)  NULL,
    google_defer_request_json  LONGTEXT     NULL,
    google_defer_response_json LONGTEXT     NULL,
    google_defer_http_status   INT          NULL,

    error_code                 VARCHAR(64)  NULL,
    error_message              VARCHAR(500) NULL,

    days_added                 INT          NOT NULL,
    old_premium_until          DATETIME(6)  NULL,
    new_premium_until          DATETIME(6)  NULL,
    next_retry_at_utc          DATETIME(6)  NULL,

    granted_at_utc             DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    INDEX idx_membership_reward_user_granted
        (user_id, granted_at_utc),

    INDEX idx_membership_reward_source_status
        (source_type, source_ref_id, grant_status),

    INDEX idx_membership_reward_trace
        (trace_id),

    INDEX idx_membership_reward_google_token
        (google_purchase_token_hash),

    INDEX idx_membership_reward_channel_status
        (reward_channel, google_defer_status),

    INDEX idx_reward_ledger_claim_channel_attempt
        (source_type, source_ref_id, reward_channel, attempt_no),

    INDEX idx_reward_ledger_in_progress
        (grant_status, reward_channel, next_retry_at_utc)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
