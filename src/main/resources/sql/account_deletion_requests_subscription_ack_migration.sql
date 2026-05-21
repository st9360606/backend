-- Account deletion compliance migration
-- Purpose:
-- 1. Keep an audit trail that an active Google Play subscription/free-trial warning was shown and acknowledged.
-- 2. Preserve the active entitlement snapshot at deletion request time for billing/customer-support/review traceability.

SET @schema_name := DATABASE();

SET @sql := IF(
    NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account_deletion_requests'
          AND COLUMN_NAME = 'subscription_warning_acknowledged'
    ),
    'ALTER TABLE account_deletion_requests ADD COLUMN subscription_warning_acknowledged BOOLEAN NOT NULL DEFAULT FALSE AFTER completed_at_utc',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account_deletion_requests'
          AND COLUMN_NAME = 'user_requested_google_play_cancel'
    ),
    'ALTER TABLE account_deletion_requests ADD COLUMN user_requested_google_play_cancel BOOLEAN NOT NULL DEFAULT FALSE AFTER subscription_warning_acknowledged',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account_deletion_requests'
          AND COLUMN_NAME = 'has_active_google_play_subscription_at_request'
    ),
    'ALTER TABLE account_deletion_requests ADD COLUMN has_active_google_play_subscription_at_request BOOLEAN NOT NULL DEFAULT FALSE AFTER user_requested_google_play_cancel',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account_deletion_requests'
          AND COLUMN_NAME = 'active_entitlement_type_at_request'
    ),
    'ALTER TABLE account_deletion_requests ADD COLUMN active_entitlement_type_at_request VARCHAR(16) NULL AFTER has_active_google_play_subscription_at_request',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account_deletion_requests'
          AND COLUMN_NAME = 'active_entitlement_source_at_request'
    ),
    'ALTER TABLE account_deletion_requests ADD COLUMN active_entitlement_source_at_request VARCHAR(32) NULL AFTER active_entitlement_type_at_request',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account_deletion_requests'
          AND COLUMN_NAME = 'active_product_id_at_request'
    ),
    'ALTER TABLE account_deletion_requests ADD COLUMN active_product_id_at_request VARCHAR(128) NULL AFTER active_entitlement_source_at_request',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account_deletion_requests'
          AND COLUMN_NAME = 'active_valid_to_utc_at_request'
    ),
    'ALTER TABLE account_deletion_requests ADD COLUMN active_valid_to_utc_at_request DATETIME(6) NULL AFTER active_product_id_at_request',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
