-- ============================================================
-- Table: user_ai_quota_state
-- Purpose:
-- 1. Track per-user AI usage quota by local day and month.
-- 2. Support cooldown and abuse throttling.
-- 3. Force low-cost AI mode temporarily when abuse rules are triggered.
-- ============================================================

CREATE TABLE IF NOT EXISTS user_ai_quota_state
(
    user_id             BIGINT      NOT NULL,

    -- Format: yyyy-MM-dd@tz
    -- Example: 2026-06-23@Asia/Taipei
    daily_key           VARCHAR(64) NOT NULL,

    daily_count         INT         NOT NULL DEFAULT 0,

    -- Format: yyyy-MM@tz
    -- Example: 2026-06@Asia/Taipei
    monthly_key         VARCHAR(64) NOT NULL,

    monthly_count       INT         NOT NULL DEFAULT 0,

    cooldown_strikes    INT         NOT NULL DEFAULT 0,
    next_allowed_at_utc DATETIME(6) NULL,

    -- When abuse protection is triggered, force LOW mode until this UTC time.
    force_low_until_utc DATETIME(6) NULL,

    cooldown_reason     VARCHAR(16) NULL,

    updated_at_utc      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (user_id),

    INDEX idx_ai_quota_daily
        (daily_key),

    INDEX idx_ai_quota_monthly
        (monthly_key)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
