-- ============================================================
-- BiteCal / Calai DEV ONLY SQL
-- Safe Update Mode fixed version
--
-- 用途：
-- 1. 完整硬刪單一 user 測試資料
-- 2. Restore Subscription 測試：把 A 標記 DELETED，但保留 user_entitlements
-- 3. 把 B 帳號 reset 成 FREE
--
-- 注意：
-- 1. 僅限 DEV / local DB 使用，不要用在正式環境。
-- 2. 如果要測「A 刪帳後 B 恢復訂閱」，A 請用第 2 區塊，不要用第 1 區塊。
-- 3. MySQL Workbench 若開啟 Safe Update Mode，本腳本會暫時 SET SQL_SAFE_UPDATES = 0，結束後恢復原值。
-- 4. 執行前請先確認 @target_user_id。
-- ============================================================


-- ============================================================
-- 0. 共用：暫時關閉 Safe Update Mode
-- ============================================================

SET @OLD_SQL_SAFE_UPDATES := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;


-- ============================================================
-- 1. DEV ONLY：完整清除單一 user 的所有帳號資料
--
-- 適用：
-- - 清掉 B 測試帳號
-- - 清掉髒資料
-- - 讓某個測試帳號完全重來
--
-- 不適用：
-- - 不要用在「要測 Restore Subscription 的 A 帳號」
-- - 因為本區塊會刪掉 user_entitlements
-- ============================================================

SET @target_user_id := 1;

-- 如果你想用 email 找 user，可以改用這段：
-- SET @target_email_input := 'restore_b@test.com';
-- SET @target_user_id := (
--     SELECT id
--     FROM users
--     WHERE email = @target_email_input
--     ORDER BY id DESC
--     LIMIT 1
-- );

SET @target_email := (
    SELECT email
    FROM users
    WHERE id = @target_user_id
    LIMIT 1
);

SELECT
    @target_user_id AS target_user_id,
    @target_email AS target_email;

SELECT
    id,
    email,
    google_sub,
    provider,
    status,
    deleted_at_utc,
    created_at,
    updated_at
FROM users
WHERE id = @target_user_id;

START TRANSACTION;

-- ============================================================
-- 1-1. 暫存關聯 id，避免刪到一半查不到
-- ============================================================

DROP TEMPORARY TABLE IF EXISTS tmp_target_food_log_ids;
CREATE TEMPORARY TABLE tmp_target_food_log_ids (
                                                   id CHAR(36) PRIMARY KEY
) ENGINE = MEMORY;

INSERT IGNORE INTO tmp_target_food_log_ids (id)
SELECT id
FROM food_logs
WHERE user_id = @target_user_id;


DROP TEMPORARY TABLE IF EXISTS tmp_target_referral_claim_ids;
CREATE TEMPORARY TABLE tmp_target_referral_claim_ids (
                                                         id BIGINT PRIMARY KEY
) ENGINE = MEMORY;

INSERT IGNORE INTO tmp_target_referral_claim_ids (id)
SELECT id
FROM referral_claims
WHERE inviter_user_id = @target_user_id
   OR invitee_user_id = @target_user_id;


-- ============================================================
-- 1-2. Food log 子表先刪，避免 FK 擋住
-- ============================================================

DELETE FROM food_log_overrides
WHERE food_log_id IN (
    SELECT id FROM tmp_target_food_log_ids
);

DELETE FROM food_log_tasks
WHERE food_log_id IN (
    SELECT id FROM tmp_target_food_log_ids
);

DELETE FROM deletion_jobs
WHERE user_id = @target_user_id
   OR food_log_id IN (
    SELECT id FROM tmp_target_food_log_ids
);

DELETE FROM food_log_requests
WHERE user_id = @target_user_id
   OR food_log_id IN (
    SELECT id FROM tmp_target_food_log_ids
);

DELETE FROM image_blobs
WHERE user_id = @target_user_id;

DELETE FROM food_logs
WHERE user_id = @target_user_id;

DELETE FROM usage_counters
WHERE user_id = @target_user_id;


-- ============================================================
-- 1-3. 每日統計 / 體重 / 喝水 / 運動 / fasting / AI quota
-- ============================================================

DELETE FROM user_daily_nutrition_summary
WHERE user_id = @target_user_id;

DELETE FROM user_daily_activity
WHERE user_id = @target_user_id;

DELETE FROM user_daily_workout_summary
WHERE user_id = @target_user_id;

DELETE FROM user_water_daily
WHERE user_id = @target_user_id;

DELETE FROM weight_history
WHERE user_id = @target_user_id;

DELETE FROM weight_timeseries
WHERE user_id = @target_user_id;

DELETE FROM workout_session
WHERE user_id = @target_user_id;

DELETE FROM workout_alias_event
WHERE user_id = @target_user_id;

DELETE FROM fasting_plan
WHERE user_id = @target_user_id;

DELETE FROM user_ai_quota_state
WHERE user_id = @target_user_id;


-- ============================================================
-- 1-4. Referral / Reward / Notification / Email outbox
-- ============================================================

DELETE FROM referral_risk_signals
WHERE claim_id IN (
    SELECT id FROM tmp_target_referral_claim_ids
);

DELETE FROM membership_reward_ledger
WHERE user_id = @target_user_id
   OR (
    source_type = 'REFERRAL_CLAIM'
        AND source_ref_id IN (
        SELECT id FROM tmp_target_referral_claim_ids
    )
    );

DELETE FROM user_notifications
WHERE user_id = @target_user_id
   OR (
    source_type = 'REFERRAL_CLAIM'
        AND source_ref_id IN (
        SELECT id FROM tmp_target_referral_claim_ids
    )
    );

DELETE FROM email_outbox
WHERE user_id = @target_user_id
   OR (
    @target_email IS NOT NULL
        AND to_email = @target_email
    );

DELETE FROM referral_case_snapshot
WHERE inviter_user_id = @target_user_id;

DELETE FROM user_referral_codes
WHERE user_id = @target_user_id;

DELETE FROM referral_claims
WHERE inviter_user_id = @target_user_id
   OR invitee_user_id = @target_user_id;


-- ============================================================
-- 1-5. Entitlement / Billing audit
--
-- 注意：
-- 這裡會刪掉訂閱權益資料。
-- 如果要測 A 刪帳後 B restore，不要對 A 執行本區塊。
-- ============================================================

DELETE FROM entitlement_transfer_audit
WHERE old_user_id = @target_user_id
   OR new_user_id = @target_user_id;

DELETE FROM user_entitlements
WHERE user_id = @target_user_id;


-- ============================================================
-- 1-6. Account deletion request / auth / profile / login code
-- ============================================================

DELETE FROM account_deletion_requests
WHERE user_id = @target_user_id;

DELETE FROM auth_tokens
WHERE user_id = @target_user_id;

DELETE FROM user_profiles
WHERE user_id = @target_user_id;

DELETE FROM email_login_codes
WHERE @target_email IS NOT NULL
  AND email = @target_email;


-- ============================================================
-- 1-7. 最後刪 users
-- ============================================================

DELETE FROM users
WHERE id = @target_user_id;


-- ============================================================
-- 1-8. 清理 temporary tables
-- ============================================================

DROP TEMPORARY TABLE IF EXISTS tmp_target_food_log_ids;
DROP TEMPORARY TABLE IF EXISTS tmp_target_referral_claim_ids;

COMMIT;


-- ============================================================
-- 2. DEV ONLY：Restore Subscription 測試專用
--
-- 用途：
-- - 模擬 A 帳號已刪除
-- - 清掉 A 的登入 token / profile / 個資
-- - 保留 A 的 user_entitlements，讓 B 可以 Restore
--
-- 使用方式：
-- - 如果你要跑這區，請單獨執行這區。
-- - 不要在同一次執行中又跑第 1 區完整硬刪。
-- ============================================================

SET @target_user_id := 1;

SET @target_email := (
    SELECT email
    FROM users
    WHERE id = @target_user_id
    LIMIT 1
);

SELECT
    @target_user_id AS target_user_id,
    @target_email AS target_email;

SELECT
    id,
    email,
    google_sub,
    provider,
    status,
    deleted_at_utc,
    created_at,
    updated_at
FROM users
WHERE id = @target_user_id;

START TRANSACTION;

DELETE FROM auth_tokens
WHERE user_id = @target_user_id;

DELETE FROM user_profiles
WHERE user_id = @target_user_id;

DELETE FROM fasting_plan
WHERE user_id = @target_user_id;

DELETE FROM user_ai_quota_state
WHERE user_id = @target_user_id;

DELETE FROM user_daily_nutrition_summary
WHERE user_id = @target_user_id;

DELETE FROM user_daily_activity
WHERE user_id = @target_user_id;

DELETE FROM user_daily_workout_summary
WHERE user_id = @target_user_id;

DELETE FROM user_water_daily
WHERE user_id = @target_user_id;

DELETE FROM weight_history
WHERE user_id = @target_user_id;

DELETE FROM weight_timeseries
WHERE user_id = @target_user_id;

DELETE FROM workout_session
WHERE user_id = @target_user_id;

DELETE FROM workout_alias_event
WHERE user_id = @target_user_id;

DELETE FROM email_login_codes
WHERE @target_email IS NOT NULL
  AND email = @target_email;

UPDATE users
SET
    status = 'DELETED',
    deleted_at_utc = UTC_TIMESTAMP(6),
    deleted_email_hash = CASE
        WHEN @target_email IS NULL THEN deleted_email_hash
        ELSE SHA2(LOWER(TRIM(@target_email)), 256)
    END,
    email = CONCAT('deleted+', id, '+', UNIX_TIMESTAMP(), '@deleted.local'),
    google_sub = NULL,
    password_hash = NULL,
    name = NULL,
    picture = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE id = @target_user_id;

SELECT
    id,
    user_id,
    entitlement_type,
    status,
    product_id,
    purchase_token_hash,
    valid_from_utc,
    valid_to_utc
FROM user_entitlements
WHERE user_id = @target_user_id
ORDER BY valid_to_utc DESC;

COMMIT;


-- ============================================================
-- 3. DEV ONLY：把 B 帳號變回 FREE
--
-- 用途：
-- - B restore 前，需要讓 B 沒有有效 entitlement。
-- - 這段只清 B 的 entitlement / transfer audit，不刪 profile。
-- ============================================================

SET @target_user_id := 1;

START TRANSACTION;

DELETE FROM user_entitlements
WHERE user_id = @target_user_id;

DELETE FROM entitlement_transfer_audit
WHERE old_user_id = @target_user_id
   OR new_user_id = @target_user_id;

COMMIT;


-- ============================================================
-- 4. 共用：恢復原本 Safe Update Mode
-- ============================================================

SET SQL_SAFE_UPDATES = @OLD_SQL_SAFE_UPDATES;



/**
  | Case  | 前置                        | 操作                   | 預期                           |
| ----- | ------------------------- | -------------------- | ---------------------------- |
| AC-01 | A `TRIAL` 未過期，A 刪帳        | B 按 Restore          | B = `TRIAL`                  |
| AC-02 | A `TRIAL` 已取消但未過期，A 刪帳    | B 按 Restore          | B = `TRIAL`                  |
| AC-03 | A `TRIAL` 已過期，A 刪帳        | B 按 Restore          | B = `FREE`                   |
| AC-04 | A `YEARLY` 未過期，A 刪帳       | B 按 Restore          | B = `PREMIUM`                |
| AC-05 | A `MONTHLY` 未過期，A 刪帳      | B 按 Restore          | B = `PREMIUM`                |
| AC-06 | A paid 已取消但未過期，A 刪帳       | B 按 Restore          | B = `PREMIUM`                |
| AC-07 | A paid 已過期，A 刪帳           | B 按 Restore          | B = `FREE`                   |
| AC-08 | A paid grace period，A 刪帳  | B 按 Restore          | B = `PREMIUM + paymentIssue` |
| AC-09 | A paid on hold，A 刪帳       | B 按 Restore          | B = `FREE`                   |
| AC-10 | A active 未刪帳              | B 按 Restore          | B 不可恢復                       |
| AC-11 | A 刪帳，B 不按 Restore         | B 點購買                | 不自動恢復，提示 Restore             |
| AC-12 | B 無 Play 訂閱               | B 點購買                | 新購買成功，B = `PREMIUM / TRIAL`  |
| AC-13 | Play `ITEM_ALREADY_OWNED` | App 收 billing result | 不 sync，只顯示 Restore           |
| AC-14 | B 關閉 Dialog               | 回 Home               | 本 session 不再跳                |
| AC-15 | B 到 Settings 點 Restore    | 手動 Restore           | 可以重新打開 Dialog                |

 */