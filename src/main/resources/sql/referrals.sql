-- Referral / reward / inbox / email outbox
CREATE TABLE IF NOT EXISTS user_referral_codes
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    promo_code     VARCHAR(24)  NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at_utc DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_user_referral_codes_user (user_id),
    UNIQUE KEY ux_user_referral_codes_code (promo_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS referral_claims
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    inviter_user_id           BIGINT       NOT NULL,
    invitee_user_id           BIGINT       NOT NULL,
    promo_code                VARCHAR(24)  NOT NULL,
    status                    VARCHAR(32)  NOT NULL,
    reject_reason             VARCHAR(64)  NOT NULL DEFAULT 'NONE',
    subscribed_at_utc         DATETIME(6)  NULL,
    qualified_at_utc          DATETIME(6)  NULL,
    verification_deadline_utc DATETIME(6)  NULL,
    rewarded_at_utc           DATETIME(6)  NULL,
    refund_detected_at_utc    DATETIME(6)  NULL,
    auto_renew_status         VARCHAR(16)  NULL,
    purchase_token_hash       CHAR(64)     NULL,
    risk_score                INT          NULL,
    risk_decision             VARCHAR(16)  NULL,
    created_at_utc            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_referral_claims_invitee (invitee_user_id),
    UNIQUE KEY ux_referral_claims_purchase_token (purchase_token_hash),
    INDEX idx_referral_claims_inviter_status (inviter_user_id, status, verification_deadline_utc),
    INDEX idx_referral_claims_status_deadline (status, verification_deadline_utc),
    INDEX idx_referral_claims_status_updated (status, updated_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS membership_reward_ledger
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    source_type        VARCHAR(32)  NOT NULL,
    source_ref_id      BIGINT       NOT NULL,
    attempt_no         INT          NOT NULL DEFAULT 1,
    trace_id           VARCHAR(64)  NULL,
    grant_status       VARCHAR(32)  NOT NULL,
    reward_channel     VARCHAR(32)  NULL,
    google_purchase_token_hash CHAR(64) NULL,
    google_defer_status VARCHAR(32) NULL,
    google_defer_request_json LONGTEXT NULL,
    google_defer_response_json LONGTEXT NULL,
    google_defer_http_status INT NULL,
    error_code         VARCHAR(64)  NULL,
    error_message      VARCHAR(500) NULL,
    days_added         INT          NOT NULL,
    old_premium_until  DATETIME(6)  NULL,
    new_premium_until  DATETIME(6)  NULL,
    next_retry_at_utc  DATETIME(6)  NULL,
    granted_at_utc     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_membership_reward_user_granted (user_id, granted_at_utc),
    INDEX idx_membership_reward_source_status (source_type, source_ref_id, grant_status),
    INDEX idx_membership_reward_trace (trace_id),
    INDEX idx_membership_reward_google_token (google_purchase_token_hash),
    INDEX idx_membership_reward_channel_status (reward_channel, google_defer_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_reward_ledger_claim_channel_attempt
    ON membership_reward_ledger (source_type, source_ref_id, reward_channel, attempt_no);

CREATE INDEX idx_reward_ledger_in_progress
    ON membership_reward_ledger (grant_status, reward_channel, next_retry_at_utc);

CREATE TABLE IF NOT EXISTS referral_risk_signals
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    claim_id                 BIGINT       NOT NULL,
    device_hash              CHAR(64)     NULL,
    ip_hash                  CHAR(64)     NULL,
    payment_fingerprint_hash CHAR(64)     NULL,
    risk_score               INT          NOT NULL,
    risk_flags_json          JSON         NULL,
    decision                 VARCHAR(16)  NOT NULL,
    created_at_utc           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_referral_risk_claim (claim_id, created_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS referral_case_snapshot
(
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    inviter_user_id            BIGINT       NOT NULL,
    total_invited              INT          NOT NULL DEFAULT 0,
    success_count              INT          NOT NULL DEFAULT 0,
    rejected_count             INT          NOT NULL DEFAULT 0,
    pending_verification_count INT          NOT NULL DEFAULT 0,
    total_rewarded_days        INT          NOT NULL DEFAULT 0,
    current_premium_until      DATETIME(6)  NULL,
    updated_at_utc             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_referral_case_snapshot_user (inviter_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_notifications
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    title          VARCHAR(120) NOT NULL,
    message        VARCHAR(500) NOT NULL,
    deep_link      VARCHAR(128) NULL,
    source_type    VARCHAR(32)  NOT NULL,
    source_ref_id  BIGINT       NOT NULL,
    is_read        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at_utc DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_user_notifications_source (user_id, source_type, source_ref_id),
    INDEX idx_user_notifications_user_created (user_id, created_at_utc),
    INDEX idx_user_notifications_user_source (user_id, source_type, source_ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS email_outbox
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NULL,
    to_email              VARCHAR(320) NOT NULL,
    template_type         VARCHAR(32)  NOT NULL,
    template_payload_json JSON         NOT NULL,
    dedupe_key            VARCHAR(100) NOT NULL,
    retry_count           INT          NOT NULL DEFAULT 0,
    status                VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at_utc        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at_utc           DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_email_outbox_dedupe (dedupe_key),
    INDEX idx_email_outbox_status_created (status, created_at_utc),
    INDEX idx_email_outbox_user_created (user_id, created_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/**
A. 使用推薦碼當下
| 編號  | 情境                                         | 條件                                                   | API 結果            | DB / Claim 狀態          | inviter 獎勵 | 通知 / Email |
| --- | ------------------------------------------ | ---------------------------------------------------- | ----------------- | ---------------------- | ---------- | ---------- |
| A-1 | 推薦碼不存在 / 格式錯誤                              | promo code 找不到 inviter                               | `400 BAD_REQUEST` | 不建立 `referral_claims`  | ❌ 無        | ❌ 無        |
| A-2 | invitee 使用自己的推薦碼                           | `inviterUserId == inviteeUserId`                     | `400 BAD_REQUEST` | 不建立 claim              | ❌ 無        | ❌ 無        |
| A-3 | invitee 已經使用過推薦碼                           | `findByInviteeUserId(inviteeUserId).isPresent()`     | `409 CONFLICT`    | 不建立第二筆 claim           | ❌ 無        | ❌ 無        |
| A-4 | invitee 歷史已有 Google Play paid subscription | `existsAnyGooglePlayPaidSubscriptionHistory == true` | `409 CONFLICT`    | 不建立 claim              | ❌ 無        | ❌ 無        |
| A-5 | 合法新 invitee 使用有效推薦碼                        | 有效碼、非自己、未 claim、無 paid history                       | 成功                | `PENDING_SUBSCRIPTION` | ❌ 尚未       | ❌ 尚未       |


B. invitee 綁定後的訂閱情境
| 編號  | 情境                           | 條件 / 來源                                       | Claim 狀態                    | inviter 獎勵 | 冷卻期   | 通知 / Email     | 備註                                                   |
| --- | ---------------------------- | --------------------------------------------- | --------------------------- | ---------- | ----- | -------------- | ---------------------------------------------------- |
| B-1 | invitee 一直沒有訂閱               | 綁定後未付款                                        | `PENDING_SUBSCRIPTION`      | ❌ 無        | 不開始   | ❌ 無            | 目前無 timeout 自動 reject                                |
| B-2 | Google Play purchase pending | `pending == true`                             | `PENDING_SUBSCRIPTION`      | ❌ 無        | 不開始   | ❌ 無            | 等付款完成                                                |
| B-3 | pending purchase 後取消         | RTDN `SUBSCRIPTION_PENDING_PURCHASE_CANCELED` | `REJECTED`                  | ❌ 無        | 不開始   | ✅ 失敗通知 + email | reason 目前走 `REFUNDED_OR_REVOKED` 類型                  |
| B-4 | test purchase                | `testPurchase == true`                        | `REJECTED`                  | ❌ 無        | 不開始   | ✅ 失敗通知 + email | reason=`TEST_PURCHASE`                               |
| B-5 | 風控拒絕                         | `risk.denied() == true`                       | `REJECTED`                  | ❌ 無        | 不開始   | ✅ 失敗通知 + email | reason=`ABUSE_RISK`                                  |
| B-6 | 首次有效付費訂閱                     | 非 trial、非 test、非 pending、無 prior paid history | `PENDING_VERIFICATION`      | ❌ 尚未       | ✅ 7 天 | ❌ 尚未           | `verificationDeadlineUtc = subscribedAtUtc + 7 days` |
| B-7 | Google Play free trial       | `freeTrial == true`                           | 通常維持 `PENDING_SUBSCRIPTION` | ❌ 無        | 不開始   | ❌ 無            | 等 trial 轉真正 paid                                     |


C. 7 天冷卻期中
| 編號  | 情境                                      | 條件 / 來源                                       | Claim 狀態                             | inviter 獎勵           | 通知 / Email     | 備註                           |
| --- | --------------------------------------- | --------------------------------------------- | ------------------------------------ | -------------------- | -------------- | ---------------------------- |
| C-1 | 7 天內沒有退款 / revoke / chargeback / voided | 無異常                                           | 維持 `PENDING_VERIFICATION` 到 deadline | 等 final verification | ❌ 暫無           | deadline 到期後 processor 處理    |
| C-2 | 取消自動續訂但沒有退款                             | Auto-renew off / canceled renewal             | 繼續 `PENDING_VERIFICATION`            | ✅ 冷卻期後仍可能拿           | ❌ 暫無           | 取消自動續訂不等於退款                  |
| C-3 | 7 天內 refund / revoke                    | RTDN `SUBSCRIPTION_REVOKED` 或 voided purchase | `REJECTED`                           | ❌ 無                  | ✅ 失敗通知 + email | reason=`REFUNDED_OR_REVOKED` |
| C-4 | 7 天內 chargeback                         | `voidedReason == 7`                           | `REJECTED`                           | ❌ 無                  | ✅ 失敗通知 + email | reason=`CHARGEBACK`          |
| C-5 | RTDN 漏接但 voided purchases 查得到           | final verification 查到                         | `REJECTED`                           | ❌ 無                  | ✅ 失敗通知 + email | v1.5 防線                      |
| C-6 | voided purchases API 暫時失敗               | Google 429 / 500 / timeout                    | 回 `PENDING_VERIFICATION`             | ❌ 暫不發                | ❌ 暫無           | 下一輪排程再驗證                     |


D. 冷卻期結束後 final verification
| 編號  | 情境                                        | Final verification 結果 | Claim 狀態                 | inviter 獎勵       | 通知 / Email     |
| --- | ----------------------------------------- | --------------------- | ------------------------ | ---------------- | -------------- |
| D-1 | subscription 仍有效，且沒有 voided/refund/revoke | 通過                    | 進入 reward grant flow     | ✅ 依 inviter 狀態決定 | 依發獎結果          |
| D-2 | subscription 已不 active 或 expiry <= now    | 不通過                   | `REJECTED`               | ❌ 無              | ✅ 失敗通知 + email |
| D-3 | purchase token 無法解密或查不到 entitlement       | 不通過                   | `REJECTED`               | ❌ 無              | ✅ 失敗通知 + email |
| D-4 | final verification 時仍是 pending            | 不通過                   | `REJECTED`               | ❌ 無              | ✅ 失敗通知 + email |
| D-5 | final verification 發現 test purchase       | 不通過                   | `REJECTED`               | ❌ 無              | ✅ 失敗通知 + email |
| D-6 | Google API 暫時失敗                           | retry later           | 回 `PENDING_VERIFICATION` | ❌ 暫不發            | ❌ 暫無           |


E. 發獎時 inviter 狀態
| 編號  | 情境                                          | Reward Channel      | Google API                             | Ledger                                  | Claim 狀態                 | inviter 實際結果                  | 通知 / Email     |
| --- | ------------------------------------------- | ------------------- | -------------------------------------- | --------------------------------------- | ------------------------ | ----------------------------- | -------------- |
| E-1 | inviter 有有效 Google Play paid subscription   | `GOOGLE_PLAY_DEFER` | ✅ 呼叫 `subscriptionsv2.defer(+30 days)` | `GOOGLE_DEFER_IN_PROGRESS → SUCCESS`    | `SUCCESS`                | ✅ Google Play 真的延後下一次扣款 30 天  | ✅ 成功通知 + email |
| E-2 | inviter 沒有有效 Google Play paid subscription  | `BACKEND_ONLY`      | ❌ 不呼叫                                  | `SUCCESS / BACKEND_ONLY / NOT_REQUIRED` | `SUCCESS`                | ✅ App backend 給 Premium +30 天 | ✅ 成功通知 + email |
| E-3 | inviter 有 Google paid，但 purchase token 解密失敗 | `GOOGLE_PLAY_DEFER` | ❌ 不呼叫                                  | `FAILED_FINAL`                          | `REJECTED`               | ❌ 無，不 fallback                | ✅ 失敗通知 + email |
| E-4 | Google deferral service 沒啟用 / 沒 bean        | `GOOGLE_PLAY_DEFER` | ❌ 暫不可用                                 | `FAILED_RETRYABLE`                      | 回 `PENDING_VERIFICATION` | ❌ 暫不發                         | ❌ 暫無           |
| E-5 | Google defer retryable failure              | `GOOGLE_PLAY_DEFER` | ✅ 但失敗                                  | `FAILED_RETRYABLE`                      | 回 `PENDING_VERIFICATION` | ❌ 暫不發                         | ❌ 暫無           |
| E-6 | Google defer final failure                  | `GOOGLE_PLAY_DEFER` | ✅ 但 final fail                         | `FAILED_FINAL`                          | `REJECTED`               | ❌ 無，不 fallback                | ✅ 失敗通知 + email |
| E-7 | Google defer retry 超過上限                     | `GOOGLE_PLAY_DEFER` | 不再重試                                   | `FAILED_FINAL`                          | `REJECTED`               | ❌ 無                           | ✅ 失敗通知 + email |
| E-8 | Google defer retry 超過 24 小時                 | `GOOGLE_PLAY_DEFER` | 不再重試                                   | `FAILED_FINAL`                          | `REJECTED`               | ❌ 無                           | ✅ 失敗通知 + email |


F. Crash / 重試 / 冪等
| 編號  | 情境                                                 | crash / retry 前狀態                          | recovery / 後續處理                                         | 是否會 double defer            | 最終結果             |
| --- | -------------------------------------------------- | ------------------------------------------ | ------------------------------------------------------- | --------------------------- | ---------------- |
| F-1 | claim 變 `PROCESSING_REWARD` 後 crash                | claim=`PROCESSING_REWARD`                  | 30 分鐘後撈出，改回 `PENDING_VERIFICATION`                      | ❌ 不會直接 double defer         | 視後續 reward grant |
| F-2 | 已寫 `GOOGLE_DEFER_IN_PROGRESS`，但尚未呼叫 Google 就 crash | ledger=`GOOGLE_DEFER_IN_PROGRESS`          | stale 後 reconcile，Google expiry 沒變 → `FAILED_RETRYABLE` | ❌ 不會直接重複                    | 下次可重試            |
| F-3 | Google defer 已成功，但 DB 更新前 crash                    | Google renewal date 已延後，DB 可能仍 in-progress | `getCurrentExpiry()` reconcile，補 `SUCCESS`              | ❌ 不會再次 defer                | ✅ 最終補成功          |
| F-4 | 同一 claim 被兩個 worker 同時處理                           | 兩個 worker 嘗試處理                             | `claimForRewardProcessing` 只有一個 update count=1          | ❌ 理論上不會                     | 只發一次             |

                                                                                                                                                              | F-5 | 已有成功 ledger，再次呼叫 grant                             | 已存在 `SUCCESS` ledger                       | 直接回傳既有 `RewardGrantResult`                              | ❌ 不呼叫 Google / backend-only | 冪等成功             |
G. 通知 / Email / CS Trace
| 編號  | 情境               | Notification                    | Email Outbox        | Snapshot / CS Trace | 備註                                    |
| --- | ---------------- | ------------------------------- | ------------------- | ------------------- | ------------------------------------- |
| G-1 | 成功發獎             | ✅ `Referral reward granted`     | ✅ 若 inviter 有 email | ✅ 更新                | deep link=`bitecal://premium-rewards` |
| G-2 | 失敗終態             | ✅ `Referral reward not granted` | ✅ 若 inviter 有 email | ✅ 更新                | deep link=`bitecal://referrals`       |
| G-3 | inviter 沒有 email | ✅ 仍建立 notification              | ❌ 不建立 email outbox  | ✅ 更新                | 不影響 reward                            |
| G-4 | 同一 claim 事件重複觸發  | 不重複                             | 不重複                 | ✅                   | notification/email 有 dedupe           |


H. App 端使用者實際看到的結果
| 編號  | 情境                                    | Settings / ProfileCard       | PremiumRewardsScreen                                                                         | Notification Inbox            |
| --- | ------------------------------------- | ---------------------------- | -------------------------------------------------------------------------------------------- | ----------------------------- |
| H-1 | inviter 成功拿到 Google Play defer reward | `PREMIUM / Until YYYY-MM-DD` | `Channel: Google Play billing date extended`、`Google defer: Success`、`Grant status: Success` | `Referral reward granted`     |
| H-2 | inviter 成功拿到 backend-only reward      | `PREMIUM / Until YYYY-MM-DD` | `Channel: Backend membership extension`、`Google defer: Not required`、`Grant status: Success` | `Referral reward granted`     |
| H-3 | referral 不符合資格                        | 原會員狀態不因 reward 增加            | ReferralScreen 顯示 rejected reason                                                            | `Referral reward not granted` |
| H-4 | invitee 已綁定但尚未付款                      | 不變                           | 不新增 reward success                                                                           | 通常無通知                         |
| H-5 | invitee 已付款但仍在 7 天冷卻期                 | 不變                           | 尚未新增成功 reward                                                                                | 通常無通知                         |


I. Backend-only reward 後會員狀態
| 原本 inviter 狀態                          | 是否有 active Google Play paid subscription | 發獎路線                        | Reward 後 DB                                          | Reward 後 App 顯示                 |
| -------------------------------------- | ---------------------------------------: | --------------------------- | ---------------------------------------------------- | ------------------------------- |
| FREE                                   |                                        ❌ | `BACKEND_ONLY`              | 新增 `REFERRAL_REWARD`，`valid_to=now+30d`              | `PREMIUM / Until now+30d`       |
| Trial 中                                |                              ❌ 通常不是 paid | `BACKEND_ONLY`              | 新增 `REFERRAL_REWARD`，通常以 active entitlement 到期日 +30d | `PREMIUM / Until YYYY-MM-DD`    |
| Trial 已過期                              |                                        ❌ | `BACKEND_ONLY`              | 新增 `REFERRAL_REWARD`，`valid_to=now+30d`              | `PREMIUM / Until now+30d`       |
| Google Play 已過期                        |                                        ❌ | `BACKEND_ONLY`              | 新增 `REFERRAL_REWARD`，`valid_to=now+30d`              | `PREMIUM / Until now+30d`       |
| 後台手動補會員中                               |                                        ❌ | `BACKEND_ONLY`              | 新增 `REFERRAL_REWARD`，`valid_to=oldExpiry+30d`        | `PREMIUM / Until oldExpiry+30d` |
| 之前 referral reward 會員中                 |                                        ❌ | `BACKEND_ONLY`              | 新增另一筆 `REFERRAL_REWARD`，`valid_to=oldExpiry+30d`     | `PREMIUM / Until oldExpiry+30d` |
| Google Play refund/revoke/chargeback 後 |                                        ❌ | `BACKEND_ONLY`，若仍允許拿 reward | 新增 `REFERRAL_REWARD`                                 | `PREMIUM / Until YYYY-MM-DD`    |

*/
