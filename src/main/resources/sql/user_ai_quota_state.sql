-- src/main/resources/sql/user_ai_quota_state.sql
-- MySQL 8.x
DROP TABLE IF EXISTS user_ai_quota_state;

CREATE TABLE user_ai_quota_state
(
    user_id             BIGINT      NOT NULL,
    -- yyyy-MM-dd@tz
    daily_key           VARCHAR(32) NOT NULL,
    daily_count         INT         NOT NULL DEFAULT 0,
    -- yyyy-MM@tz
    monthly_key         VARCHAR(32) NOT NULL,
    monthly_count       INT         NOT NULL DEFAULT 0,
    cooldown_strikes    INT         NOT NULL DEFAULT 0,
    next_allowed_at_utc DATETIME(6) NULL,
    -- Step 5 會用到（ABUSE 觸發時強制 LOW）
    force_low_until_utc DATETIME(6) NULL,
    cooldown_reason     VARCHAR(16) NULL,
    updated_at_utc      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (user_id),
    INDEX idx_ai_quota_daily (daily_key),
    INDEX idx_ai_quota_monthly (monthly_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;