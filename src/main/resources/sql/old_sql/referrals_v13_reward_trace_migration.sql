-- Referral v1.3 stable commercial migration.
-- Purpose:
-- 1. membership_reward_ledger becomes append-only attempt trace.
-- 2. Google Play defer request/response/http status/trace id are kept for CS.
-- 3. The old unique (source_type, source_ref_id) is removed so retry attempts are not overwritten.
-- MySQL-safe idempotent version.

-- Drop old unique constraint if it exists. It blocked append-only reward attempts.

-- 這段 SQL 代碼的主要目的是執行 「冪等性（Idempotent）索引創建」。
-- 簡單來說，它的邏輯是：「先檢查索引是否存在，如果不存在才建立，避免報錯。」

SET @ddl = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE membership_reward_ledger DROP INDEX ux_membership_reward_source_ref',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND INDEX_NAME = 'ux_membership_reward_source_ref'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- attempt_no
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN attempt_no INT NOT NULL DEFAULT 1 AFTER source_ref_id',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'attempt_no'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- trace_id
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN trace_id VARCHAR(64) NULL AFTER attempt_no',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'trace_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- widen grant_status because commercial statuses include FAILED_RETRYABLE / FAILED_FINAL.
ALTER TABLE membership_reward_ledger MODIFY COLUMN grant_status VARCHAR(32) NOT NULL;

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

-- google_defer_request_json as LONGTEXT. It can include raw request JSON for trace.
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN google_defer_request_json LONGTEXT NULL AFTER google_defer_status',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'google_defer_request_json'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- google_defer_response_json was JSON before. Change to LONGTEXT so non-2xx HTTP error bodies are preserved safely.
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN google_defer_response_json LONGTEXT NULL AFTER google_defer_request_json',
        'ALTER TABLE membership_reward_ledger MODIFY COLUMN google_defer_response_json LONGTEXT NULL'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'google_defer_response_json'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- google_defer_http_status
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN google_defer_http_status INT NULL AFTER google_defer_response_json',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'google_defer_http_status'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- error_code
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN error_code VARCHAR(64) NULL AFTER google_defer_http_status',
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

-- next_retry_at_utc
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE membership_reward_ledger ADD COLUMN next_retry_at_utc DATETIME(6) NULL AFTER new_premium_until',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND COLUMN_NAME = 'next_retry_at_utc'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_membership_reward_source_status
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_membership_reward_source_status ON membership_reward_ledger (source_type, source_ref_id, grant_status)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND INDEX_NAME = 'idx_membership_reward_source_status'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_membership_reward_trace
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_membership_reward_trace ON membership_reward_ledger (trace_id)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'membership_reward_ledger'
      AND INDEX_NAME = 'idx_membership_reward_trace'
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

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_referral_claims_status_updated ON referral_claims (status, updated_at_utc)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'referral_claims'
      AND INDEX_NAME = 'idx_referral_claims_status_updated'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;