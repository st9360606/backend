-- 直接讓 A 帳號獲得 30 天 Premium
START TRANSACTION;

SET @claim_id := 4;
SET @inviter_user_id := 1; -- A 帳號
SET @invitee_user_id := 2; -- B 帳號
SET @now := UTC_TIMESTAMP(6);
SET @manual_hash := SHA2(CONCAT('manual-db-referral-reward:', @claim_id, ':', @inviter_user_id), 256);

-- 1) 取得 A 目前有效 Premium 的最大到期日。
-- 如果 A 目前沒有 Premium，就從現在開始加 30 天。
SELECT
    @old_until := COALESCE(MAX(valid_to_utc), @now)
FROM caloshape.user_entitlements
WHERE user_id = @inviter_user_id
  AND status = 'ACTIVE'
  AND valid_to_utc > @now;

SET @new_until := DATE_ADD(@old_until, INTERVAL 30 DAY);

-- 2) 把 referral claim 直接改成成功
UPDATE caloshape.referral_claims
SET
    status = 'SUCCESS',
    reject_reason = '',
    subscribed_at_utc = COALESCE(subscribed_at_utc, @now),
    qualified_at_utc = COALESCE(qualified_at_utc, @now),
    verification_deadline_utc = COALESCE(verification_deadline_utc, @now - INTERVAL 1 MINUTE),
    rewarded_at_utc = @now,
    purchase_token_hash = COALESCE(
            purchase_token_hash,
            (
                SELECT ue.purchase_token_hash
                FROM caloshape.user_entitlements ue
                WHERE ue.user_id = @invitee_user_id
                  AND ue.status = 'ACTIVE'
                ORDER BY ue.valid_to_utc DESC, ue.created_at_utc DESC
                LIMIT 1
            )
                          ),
    updated_at_utc = @now
WHERE id = @claim_id
  AND inviter_user_id = @inviter_user_id
  AND invitee_user_id = @invitee_user_id;

-- 3) 幫 A 新增一筆 backend-only referral reward entitlement +30 天
INSERT INTO caloshape.user_entitlements (
    id,
    user_id,
    entitlement_type,
    status,
    valid_from_utc,
    valid_to_utc,
    purchase_token_hash,
    purchase_token_ciphertext,
    last_verified_at_utc,
    last_google_verified_at_utc,
    source,
    product_id,
    subscription_state,
    payment_state,
    created_at_utc,
    updated_at_utc
)
VALUES (
           UUID(),
           @inviter_user_id,
           'REFERRAL_REWARD',
           'ACTIVE',
           @now,
           @new_until,
           @manual_hash,
           NULL,
           @now,
           @now,
           'INTERNAL',
           NULL,
           NULL,
           'OK',
           @now,
           @now
       );

COMMIT;











-- A 原本就是 Premium 時

ROLLBACK;

START TRANSACTION;

SET @claim_id := 4;
SET @inviter_user_id := 1; -- A 帳號
SET @invitee_user_id := 2; -- B 帳號
SET @now := UTC_TIMESTAMP(6);
SET @manual_hash := SHA2(CONCAT('manual-db-referral-reward:', @claim_id, ':', @inviter_user_id), 256);

-- 1) 取得 A 目前有效 Premium 的最大到期日。
--    如果 A 目前沒有 Premium，就從現在開始。
SELECT
    @old_until := COALESCE(MAX(valid_to_utc), @now)
FROM caloshape.user_entitlements
WHERE user_id = @inviter_user_id
  AND status = 'ACTIVE'
  AND valid_to_utc > @now;

SET @reward_from := @old_until;
SET @new_until := DATE_ADD(@reward_from, INTERVAL 30 DAY);

-- 2) 把 referral claim 改成成功
UPDATE caloshape.referral_claims
SET
    status = 'SUCCESS',
    reject_reason = '',
    subscribed_at_utc = COALESCE(subscribed_at_utc, @now),
    qualified_at_utc = COALESCE(qualified_at_utc, @now),
    verification_deadline_utc = COALESCE(verification_deadline_utc, @now - INTERVAL 1 MINUTE),
    rewarded_at_utc = @now,
    purchase_token_hash = COALESCE(
            purchase_token_hash,
            (
                SELECT ue.purchase_token_hash
                FROM caloshape.user_entitlements ue
                WHERE ue.user_id = @invitee_user_id
                  AND ue.status = 'ACTIVE'
                ORDER BY ue.valid_to_utc DESC, ue.created_at_utc DESC
                LIMIT 1
            )
                          ),
    updated_at_utc = @now
WHERE id = @claim_id
  AND inviter_user_id = @inviter_user_id
  AND invitee_user_id = @invitee_user_id;

-- 3) 幫 A 新增一筆接續在原 Premium 後面的 +30 天 reward
INSERT INTO caloshape.user_entitlements (
    id,
    user_id,
    entitlement_type,
    status,
    valid_from_utc,
    valid_to_utc,
    purchase_token_hash,
    purchase_token_ciphertext,
    last_verified_at_utc,
    last_google_verified_at_utc,
    source,
    product_id,
    subscription_state,
    payment_state,
    created_at_utc,
    updated_at_utc
)
VALUES (
           UUID(),
           @inviter_user_id,
           'REFERRAL_REWARD',
           'ACTIVE',
           @reward_from,
           @new_until,
           @manual_hash,
           NULL,
           @now,
           @now,
           'INTERNAL',
           NULL,
           NULL,
           'OK',
           @now,
           @now
       );

COMMIT;


-- 如果已經推薦成功過的要測試
-- 刪除被推薦人的referral_claims、user_entitlements

-- 測試推薦成功發信
UPDATE caloshape.referral_claims
SET
    qualified_at_utc = UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE,
    cooldown_until_utc = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE,
    verification_deadline_utc = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE,
    rewarded_at_utc = NULL,
    refund_detected_at_utc = NULL,
    risk_score = 0,
    risk_decision = 'ALLOW',
    reject_reason = 'NONE'
WHERE id = 8
  AND inviter_user_id = 1
  AND invitee_user_id = 2
  AND status = 'PENDING_COOLDOWN';

-- 再執行下面這個
# curl.exe -X POST "http://localhost:8080/internal/referrals/process-pending" -H "X-Internal-Token: dev-internal-token-change-me"