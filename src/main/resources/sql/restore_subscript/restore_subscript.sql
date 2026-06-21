

-- ============================================================
-- 2. DEV ONLY：把 B 帳號變回 FREE
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