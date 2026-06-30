-- Existing DB migration for Google Play payment state hardening.
-- MySQL-safe idempotent version.
-- 可重複執行；欄位或 index 已存在時不會失敗。

-- entitlement_type 放寬，支援 REFERRAL_REWARD 與未來類型。
ALTER TABLE user_entitlements
    MODIFY COLUMN entitlement_type VARCHAR(32) NOT NULL;

-- purchase_token_ciphertext
SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'ALTER TABLE user_entitlements ADD COLUMN purchase_token_ciphertext VARCHAR(2048) NULL AFTER purchase_token_hash',
                   'SELECT 1'
           )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_entitlements'
      AND COLUMN_NAME = 'purchase_token_ciphertext'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- last_google_verified_at_utc
SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'ALTER TABLE user_entitlements ADD COLUMN last_google_verified_at_utc DATETIME(6) NULL AFTER last_verified_at_utc',
                   'SELECT 1'
           )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_entitlements'
      AND COLUMN_NAME = 'last_google_verified_at_utc'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- payment_state
SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'ALTER TABLE user_entitlements ADD COLUMN payment_state VARCHAR(32) NULL AFTER subscription_state',
                   'SELECT 1'
           )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_entitlements'
      AND COLUMN_NAME = 'payment_state'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- grace_until_utc
SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'ALTER TABLE user_entitlements ADD COLUMN grace_until_utc DATETIME(6) NULL AFTER payment_state',
                   'SELECT 1'
           )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_entitlements'
      AND COLUMN_NAME = 'grace_until_utc'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- close_reason
SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'ALTER TABLE user_entitlements ADD COLUMN close_reason VARCHAR(64) NULL AFTER grace_until_utc',
                   'SELECT 1'
           )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_entitlements'
      AND COLUMN_NAME = 'close_reason'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_entitlements_reverify
SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'CREATE INDEX idx_entitlements_reverify ON user_entitlements (source, status, last_google_verified_at_utc)',
                   'SELECT 1'
           )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_entitlements'
      AND INDEX_NAME = 'idx_entitlements_reverify'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_entitlements_payment_state
SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'CREATE INDEX idx_entitlements_payment_state ON user_entitlements (payment_state, status, valid_to_utc)',
                   'SELECT 1'
           )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_entitlements'
      AND INDEX_NAME = 'idx_entitlements_payment_state'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Disable legacy backend-issued internal trial rows.
-- Official trials must come from Google Play offerPhase = FREE_TRIAL.
UPDATE user_entitlements
SET status = 'EXPIRED',
    payment_state = 'EXPIRED',
    close_reason = 'LEGACY_INTERNAL_TRIAL_DISABLED',
    valid_to_utc = CASE
                       WHEN valid_to_utc > UTC_TIMESTAMP(6)
                           THEN UTC_TIMESTAMP(6)
                       ELSE valid_to_utc
        END,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE source = 'INTERNAL'
  AND entitlement_type = 'TRIAL'
  AND status = 'ACTIVE';
