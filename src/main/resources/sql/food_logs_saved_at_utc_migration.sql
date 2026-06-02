-- Adds explicit favorite timestamp for saved-food ordering.
-- Existing SAVED rows are backfilled with the migration execution time.

SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'ALTER TABLE food_logs ADD COLUMN saved_at_utc DATETIME(6) NULL AFTER updated_at_utc',
                   'SELECT 1'
           )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'food_logs'
      AND COLUMN_NAME = 'saved_at_utc'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE food_logs
SET saved_at_utc = UTC_TIMESTAMP(6)
WHERE id >= '00000000-0000-0000-0000-000000000000'
  AND status = 'SAVED'
  AND saved_at_utc IS NULL;

SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'CREATE INDEX idx_food_logs_user_status_saved_at ON food_logs (user_id, status, saved_at_utc)',
                   'SELECT 1'
           )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'food_logs'
      AND INDEX_NAME = 'idx_food_logs_user_status_saved_at'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
                   COUNT(*) = 0,
                   'CREATE INDEX idx_food_logs_user_sha_status_created ON food_logs (user_id, image_sha256, status, created_at_utc)',
                   'SELECT 1'
           )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'food_logs'
      AND INDEX_NAME = 'idx_food_logs_user_sha_status_created'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
