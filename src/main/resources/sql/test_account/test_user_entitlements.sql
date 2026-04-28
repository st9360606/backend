
# 1. 情境 A：已付費月訂閱中 MONTHLY + ACTIVE
# 預期：Onboarding 登入後直接進 HOME。

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 30 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-ACTIVE-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


# 2. 情境 B：已付費年訂閱中 YEARLY + ACTIVE
# 預期：Onboarding 登入後直接進 HOME。

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 20 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 365 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-ACTIVE-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';



# 3. 情境 C：Trial 試用中 TRIAL + ACTIVE
# 預期：Onboarding 登入後直接進 HOME。

UPDATE user_entitlements
SET
    entitlement_type = 'TRIAL',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 2 DAY,
    source = 'INTERNAL',
    product_id = NULL,
    subscription_state = 'TRIAL_ACTIVE',
    offer_phase = 'FREE_TRIAL',
    auto_renew_enabled = NULL,
    acknowledgement_state = NULL,
    latest_order_id = NULL,
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = NULL,
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


# 4. 情境 D：Trial 已過期 TRIAL + EXPIRED
# 預期：Onboarding 登入後進 ONBOARD_SUBSCRIPTION。

UPDATE user_entitlements
SET
    entitlement_type = 'TRIAL',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 5 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 2 DAY,
    source = 'INTERNAL',
    product_id = NULL,
    subscription_state = 'TRIAL_EXPIRED',
    offer_phase = 'FREE_TRIAL',
    auto_renew_enabled = NULL,
    acknowledgement_state = NULL,
    latest_order_id = NULL,
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = NULL,
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';



# 5. 情境 E：已取消續訂，但付費期間尚未結束
# 預期：仍然直接進 HOME。
# 這個情境很重要，因為使用者取消續訂後，在到期日前仍然是付費會員。

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 15 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 15 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_CANCELED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-CANCELED-BUT-VALID-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


# 6. 情境 F：月訂閱已過期 MONTHLY + EXPIRED
# 預期：Onboarding 登入後進訂閱頁。

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 45 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 15 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_EXPIRED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

# 情境 J：年訂閱已取消續訂，但權益尚未到期
# 預期：直接進 HOME。

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 100 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) + INTERVAL 265 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_CANCELED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-CANCELED-BUT-VALID-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

# 情境 K：年訂閱已過期
# 預期：進 ONBOARD_SUBSCRIPTION 或一般 SUBSCRIPTION。

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'EXPIRED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 400 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 35 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_EXPIRED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

# 情境 L：年訂閱取消且已過期
# 預期：不可進 HOME。

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'CANCELLED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 400 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 20 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_CANCELED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-CANCELLED-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';



# 7. 情境 G：訂閱被撤銷 / 退款 REVOKED
# 預期：不能進 HOME，應該進訂閱頁。

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'REVOKED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 10 DAY,
    valid_to_utc = UTC_TIMESTAMP(6),
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_REVOKED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-MONTHLY-REVOKED-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = UTC_TIMESTAMP(6),
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


# 情境 M：年訂閱被退款 / 撤銷 YEARLY + REVOKED
# 預期：進 ONBOARD_SUBSCRIPTION 或一般 SUBSCRIPTION，不可直接進 HOME。

UPDATE user_entitlements
SET
    entitlement_type = 'YEARLY',
    status = 'REVOKED',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 120 DAY,
    valid_to_utc = UTC_TIMESTAMP(6),
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_yearly',
    subscription_state = 'SUBSCRIPTION_STATE_REVOKED',
    offer_phase = 'BASE',
    auto_renew_enabled = FALSE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-YEARLY-REVOKED-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = UTC_TIMESTAMP(6),
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


# 8. 情境 H：故意測髒資料：ACTIVE 但已過期
# 預期：理論上應該不能進 HOME。
# 這是拿來測你的後端是否真的有檢查 valid_to_utc > now。

UPDATE user_entitlements
SET
    entitlement_type = 'MONTHLY',
    status = 'ACTIVE',
    valid_from_utc = UTC_TIMESTAMP(6) - INTERVAL 45 DAY,
    valid_to_utc = UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
    source = 'GOOGLE_PLAY',
    product_id = 'calai_premium_monthly',
    subscription_state = 'SUBSCRIPTION_STATE_ACTIVE',
    offer_phase = 'BASE',
    auto_renew_enabled = TRUE,
    acknowledgement_state = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    latest_order_id = 'DEV-DIRTY-ACTIVE-BUT-EXPIRED-001',
    linked_purchase_token_hash = NULL,
    last_verified_at_utc = UTC_TIMESTAMP(6),
    last_rtdn_at_utc = UTC_TIMESTAMP(6),
    revoked_at_utc = NULL,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1
  AND purchase_token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';


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