-- Referral v1.3 reward trace migration.
-- MySQL-safe idempotent version.
-- 可重複執行；欄位或 index 已存在時不會失敗。

-- reward_channel
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN reward_channel VARCHAR(32) NULL AFTER grant_status',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'reward_channel'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- google_purchase_token_hash
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN google_purchase_token_hash CHAR(64) NULL AFTER reward_channel',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'google_purchase_token_hash'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- google_defer_status
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN google_defer_status VARCHAR(32) NULL AFTER google_purchase_token_hash',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'google_defer_status'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- google_defer_response_json
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN google_defer_response_json JSON NULL AFTER google_defer_status',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'google_defer_response_json'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- error_code
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN error_code VARCHAR(64) NULL AFTER google_defer_response_json',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'error_code'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- error_message
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN error_message VARCHAR(500) NULL AFTER error_code',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'error_message'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_membership_reward_google_token
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_membership_reward_google_token ON membership_reward_ledger (google_purchase_token_hash)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND INDEX_NAME = 'idx_membership_reward_google_token'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_membership_reward_channel_status
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_membership_reward_channel_status ON membership_reward_ledger (reward_channel, google_defer_status)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND INDEX_NAME = 'idx_membership_reward_channel_status'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_user_notifications_user_source
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_user_notifications_user_source ON user_notifications (user_id, source_type, source_ref_id)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_notifications'
      AND INDEX_NAME = 'idx_user_notifications_user_source'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_email_outbox_user_created
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_email_outbox_user_created ON email_outbox (user_id, created_at_utc)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_outbox'
      AND INDEX_NAME = 'idx_email_outbox_user_created'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
