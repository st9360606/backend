-- 2026-04-25
-- Purpose:
-- Trial duplicate prevention and trial grant audit table.
-- This project currently uses ddl-auto=none and no Flyway.
-- Execute manually in MySQL Workbench.

CREATE TABLE IF NOT EXISTS user_trial_grants
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    email_hash     CHAR(64)    NOT NULL,
    device_hash    CHAR(64)    NOT NULL,
    first_user_id  BIGINT      NOT NULL,
    granted_at_utc DATETIME(6) NOT NULL,
    UNIQUE KEY uq_trial_email (email_hash),
    UNIQUE KEY uq_trial_device (device_hash),
    INDEX idx_trial_first_user (first_user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Run only if column does not exist:
-- SHOW COLUMNS FROM user_entitlements LIKE 'trial_unique_user_id';

ALTER TABLE user_entitlements
    ADD COLUMN trial_unique_user_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN entitlement_type = 'TRIAL' THEN user_id
                ELSE NULL
                END
            ) STORED;

-- Run only if index does not exist:
-- SHOW INDEX FROM user_entitlements WHERE Key_name = 'uk_user_entitlements_one_trial';

CREATE UNIQUE INDEX uk_user_entitlements_one_trial
    ON user_entitlements (trial_unique_user_id);
