-- ============================================================
-- BiteCal / Calai Subscription State Test SQL
-- user_id = 1
-- purchase_token_hash = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
--
-- 注意：
-- 1. 每次只執行一個情境。
-- 2. Trial 不再使用 INTERNAL，全部模擬 Google Play Trial。
-- 3. product_id 使用 backend yml 內的 bitecal_monthly / bitecal_yearly。
-- 4. purchase_token_ciphertext 手動測試可先設 NULL。
-- ============================================================


-- ============================================================
-- 情境 1：完全新用戶，沒有任何 entitlement history
-- 預期：
-- premiumStatus = FREE
-- trialEligible = true
-- OneTimeOffer = 可顯示 Start Free Trial
-- Camera = ❌
-- ============================================================

SET @uid = 1;

DELETE FROM user_entitlements
WHERE user_id = @uid;

-- ============================================================
-- 情境 2：Trial expired
-- 預期：
-- premiumStatus = FREE
-- trialEligible = false
-- OneTimeOffer switch disabled
-- CTA = Continue
-- Camera = ❌
-- ============================================================

SET @uid = 1;
SET @token_a = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

DELETE FROM user_entitlements
WHERE user_id = @uid;

INSERT INTO user_entitlements (
    id, user_id, entitlement_type, status,
    valid_from_utc, valid_to_utc,
    purchase_token_hash, purchase_token_ciphertext,
    last_verified_at_utc, last_google_verified_at_utc,
    source, product_id, subscription_state, payment_state,
    grace_until_utc, close_reason, offer_phase,
    auto_renew_enabled, acknowledgement_state, latest_order_id,
    linked_purchase_token_hash, last_rtdn_at_utc, revoked_at_utc,
    created_at_utc, updated_at_utc
) VALUES (
             UUID(), @uid, 'TRIAL', 'EXPIRED',
             UTC_TIMESTAMP(6) - INTERVAL 5 DAY,
             UTC_TIMESTAMP(6) - INTERVAL 2 DAY,
             @token_a, NULL,
             UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
             'GOOGLE_PLAY', 'bitecal_yearly',
             'SUBSCRIPTION_STATE_EXPIRED', 'EXPIRED',
             NULL, 'GOOGLE_PLAY_EXPIRED', 'FREE_TRIAL',
             FALSE, 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
             'DEV-TRIAL-EXPIRED-TRIAL-ELIGIBLE-FALSE-001',
             NULL, UTC_TIMESTAMP(6), NULL,
             UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
         );

-- ============================================================
-- 情境 3：Legacy dirty row：payment_state NULL + subscription_state ON_HOLD
-- 預期：
-- premiumStatus = FREE
-- Camera = ❌
-- ============================================================

SET @uid = 1;
SET @token_a = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

DELETE FROM user_entitlements
WHERE user_id = @uid;

INSERT INTO user_entitlements (
    id, user_id, entitlement_type, status,
    valid_from_utc, valid_to_utc,
    purchase_token_hash, purchase_token_ciphertext,
    last_verified_at_utc, last_google_verified_at_utc,
    source, product_id, subscription_state, payment_state,
    grace_until_utc, close_reason, offer_phase,
    auto_renew_enabled, acknowledgement_state, latest_order_id,
    linked_purchase_token_hash, last_rtdn_at_utc, revoked_at_utc,
    created_at_utc, updated_at_utc
) VALUES (
             UUID(), @uid, 'YEARLY', 'ACTIVE',
             UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
             UTC_TIMESTAMP(6) + INTERVAL 20 DAY,
             @token_a, NULL,
             UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
             'GOOGLE_PLAY', 'bitecal_yearly',
             'SUBSCRIPTION_STATE_ON_HOLD', NULL,
             NULL, 'GOOGLE_PLAY_ON_HOLD', 'BASE',
             TRUE, 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
             'DEV-LEGACY-ON-HOLD-PAYMENT-NULL-001',
             NULL, UTC_TIMESTAMP(6), NULL,
             UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
         );

-- ============================================================
-- 情境 4：Legacy dirty row：subscription_state NULL + payment_state ON_HOLD
-- 預期：
-- premiumStatus = FREE
-- Camera = ❌
-- ============================================================

SET @uid = 1;
SET @token_a = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

DELETE FROM user_entitlements
WHERE user_id = @uid;

INSERT INTO user_entitlements (
    id, user_id, entitlement_type, status,
    valid_from_utc, valid_to_utc,
    purchase_token_hash, purchase_token_ciphertext,
    last_verified_at_utc, last_google_verified_at_utc,
    source, product_id, subscription_state, payment_state,
    grace_until_utc, close_reason, offer_phase,
    auto_renew_enabled, acknowledgement_state, latest_order_id,
    linked_purchase_token_hash, last_rtdn_at_utc, revoked_at_utc,
    created_at_utc, updated_at_utc
) VALUES (
             UUID(), @uid, 'YEARLY', 'ACTIVE',
             UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
             UTC_TIMESTAMP(6) + INTERVAL 20 DAY,
             @token_a, NULL,
             UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
             'GOOGLE_PLAY', 'bitecal_yearly',
             NULL, 'ON_HOLD',
             NULL, 'GOOGLE_PLAY_ON_HOLD', 'BASE',
             TRUE, 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
             'DEV-LEGACY-ON-HOLD-SUBSCRIPTION-NULL-001',
             NULL, UTC_TIMESTAMP(6), NULL,
             UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
         );

-- ============================================================
-- 情境 A：月訂閱有效 MONTHLY + ACTIVE
-- 預期：
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 30 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-ACTIVE-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 B：年訂閱有效 YEARLY + ACTIVE
-- 預期：
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 20 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 365 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-ACTIVE-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 C：Google Play Trial 試用中，未取消
-- 預期：
-- App 狀態 = TRIAL
-- UI = TRIAL
-- Camera = ✅
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'TRIAL',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6),
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 3 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'FREE_TRIAL',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-GOOGLE-TRIAL-ACTIVE-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 C-2：Google Play Trial 試用中，但已取消續訂
-- 預期：
-- App 狀態 = TRIAL
-- UI = TRIAL
-- Camera = ✅ 可用到試用期結束
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'TRIAL',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 2 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_CANCELED',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'FREE_TRIAL',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-GOOGLE-TRIAL-CANCELED-BUT-VALID-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 D：Google Play Trial 已過期，未續訂
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'TRIAL',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 5 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 2 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_EXPIRED',
    payment_state = 'EXPIRED',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_EXPIRED',
    offer_phase = 'FREE_TRIAL',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-GOOGLE-TRIAL-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 E：月訂閱已取消續訂，但權益尚未到期
-- 預期：
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅ 可用到月費到期
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 15 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 15 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_CANCELED',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-CANCELED-BUT-VALID-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 F：月訂閱已過期 MONTHLY + EXPIRED
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 45 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 15 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_EXPIRED',
    payment_state = 'EXPIRED',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_EXPIRED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 G：月訂閱被退款 / 撤銷 REVOKED
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'REVOKED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
    valid_to_utc = UTC_TIMESTAMP(6),
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_REVOKED',
    payment_state = 'REVOKED',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_REVOKED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-REVOKED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = UTC_TIMESTAMP(6),
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 H：髒資料：ACTIVE 但 valid_to_utc <= now
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- 原因：
-- Repository 會要求 valid_to_utc > now
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 45 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-DIRTY-ACTIVE-BUT-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 I：第 4 天扣款成功，Trial 轉 BASE 付費
-- 預期：
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6),
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 365 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-TRIAL-TO-YEARLY-PAID-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 J：年訂閱已取消續訂，但權益尚未到期
-- 預期：
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅ 可用到年費到期
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 100 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 265 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_CANCELED',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-CANCELED-BUT-VALID-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 K：年訂閱已過期
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 400 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 35 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_EXPIRED',
    payment_state = 'EXPIRED',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_EXPIRED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 L：年訂閱取消且已過期
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 400 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 20 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_CANCELED',
    payment_state = 'EXPIRED',
    grace_until_utc = NULL,
    close_reason = 'EXPIRED_BY_TIME',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-CANCELED-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 M：年訂閱被退款 / 撤銷 YEARLY + REVOKED
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'REVOKED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 120 DAY,
    valid_to_utc = UTC_TIMESTAMP(6),
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_REVOKED',
    payment_state = 'REVOKED',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_REVOKED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-REVOKED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = UTC_TIMESTAMP(6),
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 N：扣款失敗 → Grace Period
-- 預期：
-- App 狀態 = PREMIUM
-- UI = Payment Issue
-- Camera = ✅
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 3 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 3 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
    payment_state = 'GRACE',
    grace_until_utc = UTC_TIMESTAMP(6) + INTERVAL 3 DAY,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-GRACE-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 O：Grace 結束 → Account Hold
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 20 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_ON_HOLD',
    payment_state = 'ON_HOLD',
    grace_until_utc = UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
    close_reason = 'GOOGLE_PLAY_ON_HOLD',
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-ON-HOLD-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

-- ============================================================
-- 情境 P：新訂閱付款 Pending
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- 原因：
-- 使用者尚未完成付款，不應開啟付費功能。
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6),
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 365 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_PENDING',
    payment_state = 'PENDING',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_PENDING_PURCHASE',
    offer_phase = 'BASE',
    auto_renew_enabled = NULL,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_UNSPECIFIED',
    latest_order_id = 'DEV-YEARLY-PENDING-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 Q：Pending Purchase Canceled
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
    valid_to_utc = UTC_TIMESTAMP(6),
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED',
    payment_state = 'PENDING_PURCHASE_CANCELED',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_PENDING_PURCHASE_CANCELED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_UNSPECIFIED',
    latest_order_id = 'DEV-YEARLY-PENDING-CANCELED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

-- ============================================================
-- 情境 R：Subscription Paused
-- 預期：
-- App 狀態 = FREE
-- UI = FREE
-- Camera = ❌
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 30 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 15 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_PAUSED',
    payment_state = 'ON_HOLD',
    grace_until_utc = NULL,
    close_reason = 'GOOGLE_PLAY_PAUSED',
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-PAUSED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 S：Account Hold / Pause 後恢復付款
-- 預期：
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 365 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-RECOVERED-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 T：正常續訂成功 SUBSCRIPTION_RENEWED
-- 預期：
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅
-- valid_to_utc 已延長
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 30 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 30 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-RENEWED-002',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


-- ============================================================
-- 情境 U：換方案 / linked purchase token
-- 預期：
-- 舊 MONTHLY = EXPIRED / SUPERSEDED_BY_NEW_ENTITLEMENT
-- 新 YEARLY = ACTIVE
-- App 狀態 = PREMIUM
-- UI = PREMIUM
-- Camera = ✅
--
-- 注意：
-- 1. 僅限 local/dev 測試資料使用。
-- 2. 這版不用 UPDATE，改用 DELETE + INSERT，避免 row 不存在時 0 rows affected。
-- 3. 每次執行 U 前會清掉 user_id = 1 的 entitlement，避免 bbbb token 污染後續情境。
-- ============================================================

SET @uid = 1;
SET @old_monthly_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
SET @new_yearly_token_hash  = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';

-- 只限 local/dev：
-- 清掉這個測試 user 的 entitlement，避免舊資料污染本情境與後續情境。
DELETE FROM user_entitlements
WHERE user_id = @uid;

-- ============================================================
-- 舊月訂閱：已被新方案取代
-- ============================================================

INSERT INTO user_entitlements (
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
    grace_until_utc,
    close_reason,
    offer_phase,
    auto_renew_enabled,
    acknowledgement_state,
    latest_order_id,
    linked_purchase_token_hash,
    last_rtdn_at_utc,
    revoked_at_utc,
    created_at_utc,
    updated_at_utc
) VALUES (
             UUID(),
             @uid,
             'MONTHLY',
             'EXPIRED',
             UTC_TIMESTAMP(6) - INTERVAL 20 DAY,
             UTC_TIMESTAMP(6),
             @old_monthly_token_hash,
             NULL,
             UTC_TIMESTAMP(6),
             UTC_TIMESTAMP(6),
             'GOOGLE_PLAY',
             'bitecal_monthly',
             'SUBSCRIPTION_STATE_CANCELED',
             'EXPIRED',
             NULL,
             'SUPERSEDED_BY_NEW_ENTITLEMENT',
             'BASE',
             FALSE,
             'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
             'DEV-MONTHLY-SUPERSEDED-001',
             NULL,
             UTC_TIMESTAMP(6),
             NULL,
             UTC_TIMESTAMP(6),
             UTC_TIMESTAMP(6)
         );

-- ============================================================
-- 新年訂閱：目前有效方案
-- linked_purchase_token_hash 指向舊月訂閱 token
-- ============================================================

INSERT INTO user_entitlements (
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
    grace_until_utc,
    close_reason,
    offer_phase,
    auto_renew_enabled,
    acknowledgement_state,
    latest_order_id,
    linked_purchase_token_hash,
    last_rtdn_at_utc,
    revoked_at_utc,
    created_at_utc,
    updated_at_utc
) VALUES (
             UUID(),
             @uid,
             'YEARLY',
             'ACTIVE',
             UTC_TIMESTAMP(6),
             UTC_TIMESTAMP(6) + INTERVAL 365 DAY,
             @new_yearly_token_hash,
             NULL,
             UTC_TIMESTAMP(6),
             UTC_TIMESTAMP(6),
             'GOOGLE_PLAY',
             'bitecal_yearly',
             'SUBSCRIPTION_STATE_ACTIVE',
             'OK',
             NULL,
             NULL,
             'BASE',
             TRUE,
             'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
             'DEV-YEARLY-UPGRADED-001',
             @old_monthly_token_hash,
             UTC_TIMESTAMP(6),
             NULL,
             UTC_TIMESTAMP(6),
             UTC_TIMESTAMP(6)
         );

-- ============================================================
-- 檢查目前測試資料
-- 預期：
-- 1. 有兩筆資料
-- 2. MONTHLY = EXPIRED
-- 3. YEARLY = ACTIVE
-- 4. YEARLY.linked_purchase_token_hash = MONTHLY.purchase_token_hash
-- ============================================================

SELECT
    user_id,
    entitlement_type,
    status,
    source,
    product_id,
    subscription_state,
    payment_state,
    offer_phase,
    auto_renew_enabled,
    acknowledgement_state,
    valid_from_utc,
    valid_to_utc,
    close_reason,
    purchase_token_hash,
    linked_purchase_token_hash,
    updated_at_utc
FROM user_entitlements
WHERE user_id = @uid
ORDER BY
    CASE entitlement_type
        WHEN 'YEARLY' THEN 1
        WHEN 'MONTHLY' THEN 2
        WHEN 'TRIAL' THEN 3
        ELSE 9
        END,
    valid_to_utc DESC;


-- ============================================================
-- 情境 V：同 user 同時有 Trial + Premium，但 Trial 到期日比較晚
-- 預期：
-- premiumStatus = PREMIUM
-- Camera = ✅
--
-- 目的：
-- 測 findActiveBestFirst 是否真的讓 YEARLY/MONTHLY 優先於 TRIAL。
-- 如果這個測出 TRIAL，代表 Repository order by 要改。
-- ============================================================

SET @uid = 1;
SET @token_a = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
SET @token_b = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';

DELETE
FROM user_entitlements
WHERE user_id = @uid;

-- Trial row：故意設比較晚到期
INSERT INTO user_entitlements (id, user_id, entitlement_type, status,
                               valid_from_utc, valid_to_utc,
                               purchase_token_hash, purchase_token_ciphertext,
                               last_verified_at_utc, last_google_verified_at_utc,
                               source, product_id, subscription_state, payment_state,
                               grace_until_utc, close_reason, offer_phase,
                               auto_renew_enabled, acknowledgement_state, latest_order_id,
                               linked_purchase_token_hash, last_rtdn_at_utc, revoked_at_utc,
                               created_at_utc, updated_at_utc)
VALUES (UUID(), @uid, 'TRIAL', 'ACTIVE',
        UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
        UTC_TIMESTAMP(6) + INTERVAL 30 DAY,
        @token_a, NULL,
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
        'GOOGLE_PLAY', 'bitecal_yearly',
        'SUBSCRIPTION_STATE_ACTIVE', 'OK',
        NULL, NULL, 'FREE_TRIAL',
        TRUE, 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
        'DEV-TRIAL-ACTIVE-CONFLICT-LONGER-001',
        NULL, UTC_TIMESTAMP(6), NULL,
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- Premium row：故意設比較短到期，但仍然應該優先
INSERT INTO user_entitlements (id, user_id, entitlement_type, status,
                               valid_from_utc, valid_to_utc,
                               purchase_token_hash, purchase_token_ciphertext,
                               last_verified_at_utc, last_google_verified_at_utc,
                               source, product_id, subscription_state, payment_state,
                               grace_until_utc, close_reason, offer_phase,
                               auto_renew_enabled, acknowledgement_state, latest_order_id,
                               linked_purchase_token_hash, last_rtdn_at_utc, revoked_at_utc,
                               created_at_utc, updated_at_utc)
VALUES (UUID(), @uid, 'YEARLY', 'ACTIVE',
        UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
        UTC_TIMESTAMP(6) + INTERVAL 3 DAY,
        @token_b, NULL,
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
        'GOOGLE_PLAY', 'bitecal_yearly',
        'SUBSCRIPTION_STATE_ACTIVE', 'OK',
        NULL, NULL, 'BASE',
        TRUE, 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
        'DEV-YEARLY-ACTIVE-CONFLICT-SHORTER-001',
        NULL, UTC_TIMESTAMP(6), NULL,
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- ============================================================
-- 情境 W：Google Play Purchase 尚未 acknowledge
-- 預期：
-- 短期可先視為 PREMIUM，但後端要重試 acknowledge。
-- 若長期未 acknowledge，Google Play 可能退款/取消，之後會透過 sync/RTDN/reverify 關閉。
-- ============================================================

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6),
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 30 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'bitecal_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    payment_state = 'OK',
    grace_until_utc = NULL,
    close_reason = NULL,
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_PENDING',
    latest_order_id = 'DEV-MONTHLY-ACK-PENDING-001',
    linked_purchase_token_hash = NULL,
    purchase_token_ciphertext = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_google_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';



/**
| 編號  | 情境                            | 應測                         |
| --- | -------------------------------- | --------------------------- |
| A   | 月訂閱有效                         | `PREMIUM` / Camera ✅       |
| B   | 年訂閱有效                         | `PREMIUM` / Camera ✅       |
| C   | Google Play Trial 試用中          | `TRIAL` / Camera ✅         |
| C-2 | Trial 試用中但取消                 | `TRIAL` / Camera ✅         |
| D   | Trial 已過期                      | `FREE` / Camera ❌          |
| E   | 月訂閱取消但未到期                  | `PREMIUM` / Camera ✅       |
| F   | 月訂閱過期                         | `FREE` / Camera ❌          |
| G   | 月訂閱 revoked                    | `FREE` / Camera ❌          |
| H   | 髒資料 ACTIVE 但過期               | `FREE` / Camera ❌          |
| I   | Trial 扣款成功轉 BASE              | `PREMIUM` / Camera ✅       |
| J   | 年訂閱取消但未到期                  | `PREMIUM` / Camera ✅       |
| K   | 年訂閱過期                         | `FREE` / Camera ❌          |
| L   | 年訂閱取消且過期                    | `FREE` / Camera ❌          |
| M   | 年訂閱 revoked                    | `FREE` / Camera ❌          |
| N   | Grace Period                     | `Payment Issue` / Camera ✅ |
| O   | Account Hold                     | `FREE` / Camera ❌          |
| P   | Pending purchase                 | `FREE` / Camera ❌          |
| Q   | Pending purchase canceled        | `FREE` / Camera ❌          |
| R   | Paused                           | `FREE` / Camera ❌          |
| S   | Recovered                        | `PREMIUM` / Camera ✅       |
| T   | Renewed                          | `PREMIUM` / Camera ✅       |
| U   | Upgrade / downgrade linked token | 新方案 `PREMIUM`，舊方案關閉   |
| V   | Trial + Premium conflict         | 應取 Premium                 |
| W   | Acknowledge pending              | 可用但要確保 acknowledge retry |
*/



/**
|情境 ID | 情境名稱                  | entitlement_type | status      | autoRenew  | valid_to_utc   | revoked_at_utc | subscription_state              | 預期 premiumStatus | Onboarding 登入後預期導頁              | 是否必測 |
| ----- | ------------------------ | ---------------- | ----------- | --------: | --------------  | -------------  | ------------------------------- | ----------------- | ------------------------------------ | ------: |
| A     | 月訂閱有效                | `MONTHLY`        | `ACTIVE`    |    `true`  | 未過期          | `NULL`         | `SUBSCRIPTION_STATE_ACTIVE`      | `PREMIUM`        | `HOME`                               |      ✅ |
| B     | 年訂閱有效                | `YEARLY`         | `ACTIVE`    |    `true`  | 未過期          | `NULL`         | `SUBSCRIPTION_STATE_ACTIVE`      | `PREMIUM`        | `HOME`                               |      ✅ |
| C     | Trial 試用中             | `TRIAL`          | `ACTIVE`    |    `NULL`  | 未過期          | `NULL`         | `TRIAL_ACTIVE`                   | `TRIAL`          | `HOME`                               |      ✅ |
| D     | Trial 已過期             | `TRIAL`          | `EXPIRED`   |    `NULL`  | 已過期          | `NULL`         | `TRIAL_EXPIRED`                  | `FREE`           | `ONBOARD_SUBSCRIPTION`               |      ✅ |
| E     | 月訂閱取消續訂但尚未到期    | `MONTHLY`        | `ACTIVE`    |   `false`  | 未過期          | `NULL`         | `SUBSCRIPTION_STATE_CANCELED`    | `PREMIUM`        | `HOME`                               |      ✅ |
| F     | 月訂閱已過期              | `MONTHLY`        | `EXPIRED`   |   `false`  | 已過期          | `NULL`         | `SUBSCRIPTION_STATE_EXPIRED`     | `FREE`           | `ONBOARD_SUBSCRIPTION`               |      ✅ |
| G     | 月訂閱退款 / 撤銷         | `MONTHLY`        | `REVOKED`   |   `false`  | 通常現在或過去    | 有值            | `SUBSCRIPTION_STATE_REVOKED`    | `FREE`           | `ONBOARD_SUBSCRIPTION`               |      ✅ |
| J     | 年訂閱取消續訂但尚未到期    | `YEARLY`         | `ACTIVE`    |   `false`  | 未過期          | `NULL`         | `SUBSCRIPTION_STATE_CANCELED`    | `PREMIUM`        | `HOME`                               |      ✅ |
| K     | 年訂閱已過期              | `YEARLY`         | `EXPIRED`   |   `false`  | 已過期          | `NULL`         | `SUBSCRIPTION_STATE_EXPIRED`     | `FREE`           | `ONBOARD_SUBSCRIPTION`               |      ✅ |
| L     | 年訂閱取消且已過期         | `YEARLY`         | `CANCELLED` |   `false`  | 已過期          | `NULL`         | `SUBSCRIPTION_STATE_CANCELED`    | `FREE`           | `ONBOARD_SUBSCRIPTION`               |      ✅ |
| M     | 年訂閱退款 / 撤銷         | `YEARLY`         | `REVOKED`   |   `false`  | 通常現在或過去    | 有值           | `SUBSCRIPTION_STATE_REVOKED`     | `FREE`           | `ONBOARD_SUBSCRIPTION`               |      ✅ |
| H     | 髒資料：ACTIVE 但已過期   | `MONTHLY`        | `ACTIVE`    |    `true`  | 已過期           | `NULL`        | `SUBSCRIPTION_STATE_ACTIVE`      | `FREE`           | 不可進 `HOME`                         | ✅ 防呆必測 |
*/


-- 試用3天後，處理方式
/**
| Google Play 狀態                  | 你的 App 狀態           | UI 顯示                       | Camera      |
| -------------------------------- | ---------------------- | ---------------------------- | ----------- |
| 試用中，未取消                     | `TRIAL`                | `TRIAL / 3 days left`        | 可用         |
| 試用中，已取消                     | `TRIAL`                | `TRIAL / Until YYYY-MM-DD`   | 可用到試用結束 |
| 第 4 天，扣款成功                  | `PREMIUM`              | `PREMIUM / Until YYYY-MM-DD` | 可用         |
| 第 4 天才取消，已扣年費             | `PREMIUM`              | `PREMIUM / Until YYYY-MM-DD` | 可用到年費到期 |
| 第 4 天扣款失敗但 grace period     | `PREMIUM` 				| `Payment issue`              | 可用，如果寬限期到了還是無法成功扣款則會變成不可用Camera   |
| account hold / expired          | `FREE`                  | `FREE / Upgrade`             | 不可用        |
| refunded / revoked              | `FREE`                  | `FREE / Upgrade`             | 不可用        |
*/

-- TRIAL方案、FREE方案、PREMIUM方案
/**
| Google Play 狀態                                 | 你的 App 狀態 | UI 顯示     | Camera |
| ------------------------------------------------| --------- | ------------- | ------ |
| 月訂閱有效 (`ACTIVE`, expiry > now)               | `PREMIUM` | `PREMIUM `    | ✅      |
| 年訂閱有效 (`ACTIVE`, expiry > now)               | `PREMIUM` | `PREMIUM `    | ✅      |
| 月訂閱取消續訂但尚未到期 (`CANCELED`, expiry > now) | `PREMIUM` | `PREMIUM `     | ✅ 可用到月費到期     |
| 年訂閱取消續訂但尚未到期 (`CANCELED`, expiry > now) | `PREMIUM` | `PREMIUM `     | ✅ 可用到年費到期     |
| 月訂閱已過期 (`EXPIRED`, expiry <= now)           | `FREE`    | `FREE`         | ❌      |
| 年訂閱已過期 (`EXPIRED`, expiry <= now)           | `FREE`    | `FREE`         | ❌      |
| 年訂閱取消且已過期                                 | `FREE`    | `FREE`        | ❌      |
| 月訂閱退款 / 撤銷 (`REVOKED`)                     | `FREE`    | `FREE`         | ❌      |
| 年訂閱退款 / 撤銷 (`REVOKED`)                     | `FREE`    | `FREE`          | ❌      |


| Google Play 狀態                                              | 你的 App 狀態 | UI 顯示       | Camera |
| ------------------------------------------------------------ | ------------ | ------------ | ------ |
| Trial 試用中 (`FREE_TRIAL`, expiry > now, autoRenew=true)     | `TRIAL`      | `TRIAL`      | ✅ 可用到試用結束   |
| 試用中，未取消訂閱                                              | `TRIAL`      | `TRIAL`      | ✅ 可用到試用結束     |
| 試用中，已取消訂閱 (`CANCELED`, expiry > now)                   | `TRIAL`      | `TRIAL`      | ✅ 可用到試用結束    |
| Trial 已過期（且未續訂）                                        | `FREE`       | `FREE`       | ❌      |

| Google Play 狀態                                   | 你的 App 狀態 | UI 顯示    | Camera |
| ------------------------------------------------- | -------------| --------- | ------- |
| 第 4 天扣款成功 (`ACTIVE`, BASE phase)              | `PREMIUM`    | `PREMIUM` |   ✅   |
| 第 4 天才取消（已扣年費） (`CANCELED`, expiry > now)  | `PREMIUM`    | `PREMIUM` |   ✅可用到年費到期   |

| Google Play 狀態                                   | 你的 App 狀態 | UI 顯示            | Camera |
| ------------------------------------------------- | ------------ | ----------------- | ------ |
| 第 4 天扣款失敗 → grace period (`IN_GRACE_PERIOD`)  | `PREMIUM`    | `Payment Issue`   | ✅可用，如果寬限期到了還是無法成功扣款則會變成不能用Camera功能   |
| account hold (`ON_HOLD`)                          | `FREE`       | `FREE`            | ❌     |
| expired（無付款成功）                               | `FREE`       | `FREE`            | ❌     |

| Google Play 狀態                   | 你的 App 狀態        | UI 顯示           | Camera |
| --------------------------------- | ------------------- | ---------------- | ------ |
| 髒資料：`ACTIVE` 但 `expiry <= now` | ❌ 必須強制當 `FREE` | `FREE`           | ❌      |
*/
