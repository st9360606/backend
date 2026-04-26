-- === user_entitlements（訂閱/試用）===
DROP TABLE IF EXISTS user_entitlements;
CREATE TABLE IF NOT EXISTS user_entitlements
(
    id                   CHAR(36)    NOT NULL,
    user_id              BIGINT      NOT NULL,
    entitlement_type     VARCHAR(16) NOT NULL, -- TRIAL/MONTHLY/YEARLY
    status               VARCHAR(16) NOT NULL, -- ACTIVE/EXPIRED/CANCELLED
    valid_from_utc       DATETIME(6) NOT NULL,
    valid_to_utc         DATETIME(6) NOT NULL,

    purchase_token_hash  CHAR(64)    NULL,
    last_verified_at_utc DATETIME(6) NULL,

    created_at_utc       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    INDEX idx_entitlements_user (user_id, status, valid_to_utc)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;


-- 2026-04-26
-- Purpose:
-- Add Google Play subscription metadata to user_entitlements.
-- This project currently uses ddl-auto=none and no Flyway.
-- Execute manually in MySQL Workbench before deploying backend code.

ALTER TABLE user_entitlements
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'INTERNAL' AFTER last_verified_at_utc,
    ADD COLUMN product_id VARCHAR(128) NULL AFTER source,
    ADD COLUMN subscription_state VARCHAR(64) NULL AFTER product_id,
    ADD COLUMN offer_phase VARCHAR(32) NULL AFTER subscription_state,
    ADD COLUMN auto_renew_enabled TINYINT(1) NULL AFTER offer_phase,
    ADD COLUMN acknowledgement_state VARCHAR(64) NULL AFTER auto_renew_enabled,
    ADD COLUMN latest_order_id VARCHAR(128) NULL AFTER acknowledgement_state,
    ADD COLUMN linked_purchase_token_hash CHAR(64) NULL AFTER latest_order_id,
    ADD COLUMN last_rtdn_at_utc DATETIME(6) NULL AFTER linked_purchase_token_hash,
    ADD COLUMN revoked_at_utc DATETIME(6) NULL AFTER last_rtdn_at_utc;

CREATE INDEX idx_entitlements_purchase_token_hash
    ON user_entitlements (purchase_token_hash);

CREATE INDEX idx_entitlements_source_state
    ON user_entitlements (source, subscription_state);


ALTER TABLE user_entitlements
    DROP INDEX uk_user_entitlements_one_trial;

ALTER TABLE user_entitlements
    DROP COLUMN trial_unique_user_id;