-- Referral v1.973 cooldown/expired migration.
-- Purpose:
-- 1. Add cooldown_until_utc as the commercial name of the 7-day verification deadline.
-- 2. Backfill cooldown_until_utc from legacy verification_deadline_utc.
-- 3. Rename new in-flight rows from PENDING_VERIFICATION to PENDING_COOLDOWN.
-- 4. Keep old verification_deadline_utc for backward compatibility during rollout.
-- Cross-environment version.
-- Do not hardcode schema name here. The selected database/schema must come
-- from the datasource URL, migration tool, or the SQL client's current schema.

SET SQL_SAFE_UPDATES = 0;

-- 1. Add cooldown_until_utc if missing
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE referral_claims ADD COLUMN cooldown_until_utc DATETIME(6) NULL AFTER verification_deadline_utc',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'referral_claims'
      AND COLUMN_NAME = 'cooldown_until_utc'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- 2. Backfill cooldown_until_utc from legacy verification_deadline_utc
UPDATE referral_claims
SET cooldown_until_utc = verification_deadline_utc
WHERE cooldown_until_utc IS NULL
  AND verification_deadline_utc IS NOT NULL;


-- 3. Rename old in-flight rows
UPDATE referral_claims
SET status = 'PENDING_COOLDOWN'
WHERE status = 'PENDING_VERIFICATION';


-- 4. Create index if missing
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_referral_claims_status_cooldown ON referral_claims (status, cooldown_until_utc)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'referral_claims'
      AND INDEX_NAME = 'idx_referral_claims_status_cooldown'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET SQL_SAFE_UPDATES = 1;
