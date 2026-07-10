# Referral Reward Manual Compensation Runbook

適用對象：客服人員、客服主管、營運人員  
適用功能：邀請碼 / 推薦獎勵 30 天 Premium 補償  
最後更新：2026-07-10

## 1. 這份文件是做什麼用的

當用戶邀請朋友成功訂閱後，系統原本會自動幫邀請人加 30 天 Premium。

如果邀請人本身是 Google Play 付費訂閱者，系統會優先呼叫 Google Play 的 defer 功能，讓 Google Play 訂閱續訂日往後延 30 天。

少數情況下，Google Play defer 可能最終失敗。這時候系統不會自動改用 backend-only 補償，因為這會影響訂閱與財務邏輯。客服確認後，可以使用本文件的人工補償流程，手動幫用戶在 CaloShape / BiteCal 內補 30 天 Premium。

重要提醒：

- 這個流程只補 CaloShape / BiteCal App 內的 Premium 權益。
- 這個流程不會修改 Google Play 的實際續訂日期、扣款日期或 Google Play 訂單。
- 不要對用戶說「Google Play 訂閱日期已延後」。
- 可以對用戶說「我們已在 App 內為你的 Premium 權益補上 30 天」。

## 2. 什麼時候可以使用

只有同時符合以下條件時，才可以使用人工補償：

1. 用戶是邀請人，也就是應該收到 30 天 Premium 的人。
2. 朋友已完成符合資格的付費訂閱。
3. 系統已嘗試 Google Play defer。
4. Google Play defer 已經是最終失敗，不是等待重試中。
5. 該推薦獎勵尚未成功發放過。

可以使用的典型情境：

- 用戶回報朋友已訂閱，但自己沒有收到 30 天 Premium。
- 內部查詢顯示 referral claim 被拒絕，原因是 `REWARD_GRANT_FAILED`。
- reward ledger 顯示 `GOOGLE_PLAY_DEFER` 並且 `FAILED_FINAL`。

### 2.1 朋友訂閱後不是立刻發獎

目前產品規則是：朋友完成有效付費訂閱後，推薦獎勵會先進入 7 天觀察期。

這 7 天是用來確認朋友沒有退款、撤銷訂閱、chargeback 或被 Google Play 判定為無效付款。

系統狀態通常會是：

```text
claim.status = PENDING_COOLDOWN
cooldownUntilUtc = 朋友付費訂閱時間 + 7 天
```

7 天觀察期結束後，backend 排程會處理發獎。排程大約每 10 分鐘跑一次，所以實際發獎時間通常是：

```text
朋友有效付費訂閱後 7 天 + 最多約 10 分鐘排程延遲
```

如果朋友在 7 天內退款、撤銷或 chargeback，claim 會被拒絕，不應人工補償。

客服回覆用戶時可以說：

```text
邀請獎勵會在朋友完成有效付費訂閱後，經過 7 天付款確認期才會發放。這段期間是為了確認訂閱沒有退款或撤銷。
```

注意：如果朋友只是免費試用，通常不會立刻觸發推薦獎勵。要等 Google Play / backend 確認為有效付費訂閱後，才會進入 7 天觀察期。

## 3. 什麼時候不能使用

以下情況不要使用人工補償，請升級給工程或主管確認：

- Google Play defer 還在等待重試，狀態是 `FAILED_RETRYABLE`。
- Google Play defer 還在處理中，狀態是 `GOOGLE_DEFER_IN_PROGRESS` 或 `IN_PROGRESS`。
- 朋友沒有完成付費訂閱。
- 朋友只是使用免費試用，且目前產品規則不把試用視為有效推薦獎勵。
- 該 claim 已經是 `SUCCESS`。
- 已經有成功的 reward ledger。
- 用戶要求退款、退訂、付款失敗、chargeback、濫用風險或帳號異常。
- 無法確認 claim ID 或邀請人 user ID。

## 4. 需要準備的資料

客服操作前，請先準備：

- 用戶 email 或 user ID。
- referral claim ID。
- 用戶問題描述。
- 內部客服工具權限。
- 內部 API token。

請勿把內部 API token 貼到客服回覆、截圖、工單公開備註或任何會給用戶看到的地方。

## 5. 權限與安全規則

人工補償 API 是內部 API，不是 App 給一般用戶呼叫的 API。

API 需要 header：

```text
X-Internal-Token: <內部 token>
```

### 5.1 Internal API token 是什麼，要去哪裡拿

`X-Internal-Token` 是後端內部 API 的保護密碼。沒有這個 token，客服查詢 API 和人工補償 API 都不能使用。

後端程式讀取的設定名稱是：

```text
app.internal.api-token
```

dev 環境目前設定在：

```text
backend/src/main/resources/application-dev.yml
```

dev 預設值是：

```yaml
app:
  internal:
    api-token: "dev-internal-token-change-me"
```

正式環境設定在：

```text
backend/src/main/resources/application-prod.yml
```

正式環境不是寫死在程式碼，而是讀取部署環境變數：

```yaml
app:
  internal:
    api-token: "${INTERNAL_API_TOKEN}"
```

也就是正式環境的 token 來源是：

```text
INTERNAL_API_TOKEN
```

客服要怎麼獲得 token：

1. 如果公司已有客服後台，客服不需要知道 token，後台會代替客服呼叫 internal API。
2. 如果目前只有 Postman 或內部工具，請向後端部署管理者、主管或 DevOps 取得正式環境 token。
3. 如果正式環境還沒有設定，請由工程或 DevOps 產生一組新的高強度 token，設定到 `INTERNAL_API_TOKEN`。

建議做法：

- 不建議把正式 token 直接發給所有一般客服。
- 比較安全的做法是做一個客服後台，客服登入後由後台代打 internal API。
- token 只應該給少數主管、工程、或有權限操作補償的人員。

如果 API 回傳：

```text
INTERNAL_API_TOKEN_NOT_CONFIGURED
```

代表正式環境還沒有設定 `INTERNAL_API_TOKEN`。

如果 API 回傳：

```text
INTERNAL_API_TOKEN_REQUIRED
```

代表請求沒有帶 `X-Internal-Token`。

如果 API 回傳：

```text
INVALID_INTERNAL_API_TOKEN
```

代表 token 錯誤或拿錯環境的 token。

安全規則：

- 只能在公司核准的內部客服工具、Postman workspace 或後台系統使用。
- 不要把 token 放在 Slack、LINE、email、客服回覆或截圖中。
- 不要把 raw purchase token、Google Play 訂單完整資訊、用戶敏感資料貼到公開工單。
- 如果不確定是不是正確用戶，先不要補償。

## 6. 操作流程總覽

請照順序操作：

1. 找到 referral claim ID。
2. 查詢 claim trace。
3. 確認是否符合人工補償條件。
4. 執行人工補償。
5. 再次查詢確認結果。
6. 回覆用戶。
7. 在工單留下內部備註。

## 7. Step 1：找到 referral claim ID

如果客服工具可以用 user ID 查詢，先查邀請人的 referral case：

```http
GET /internal/cs/referrals/inviter/{userId}
```

你會看到：

- `recentClaims`
- `rewardLedger`
- `notifications`
- `emailOutbox`

### 7.1 reward ledger 在哪裡看

客服要看的 reward ledger 有兩個入口：

第一個入口是查邀請人的整體案件：

```http
GET /internal/cs/referrals/inviter/{userId}
X-Internal-Token: <內部 token>
```

這個 response 裡會有：

```text
rewardLedger
```

這裡會列出該邀請人最近的 reward ledger，適合用來快速確認最近是否有發獎、失敗或人工補償。

第二個入口是查單一 claim：

```http
GET /internal/cs/referrals/claim/{claimId}
X-Internal-Token: <內部 token>
```

這個 response 裡也會有：

```text
rewardLedger
```

這裡只看該 claim 的 reward ledger，最適合拿來判斷能不能做人工補償。

客服優先順序：

1. 先用 `/inviter/{userId}` 找到正確 claim ID。
2. 再用 `/claim/{claimId}` 看該 claim 的 `rewardLedger`。
3. 不建議一般客服直接查 DB 的 `membership_reward_ledger` 表。

如果公司還沒有客服後台畫面，短期可以用 Postman 或內部 API 工具查看；長期建議做一個客服後台，把 `claim` 和 `rewardLedger` 用表格呈現。

請在 `recentClaims` 裡找到本次有問題的 claim，記下：

- `id`：這就是 claim ID。
- `inviterUserId`：應該是提出問題的用戶。
- `inviteeUserId`：被邀請的朋友。
- `status`
- `rejectReason`
- `rewardedAtUtc`

如果不知道 user ID，請先用內部客服系統依 email 查 user ID。不要猜 user ID。

## 8. Step 2：查詢 claim trace

拿到 claim ID 後，查詢完整 trace：

```http
GET /internal/cs/referrals/claim/{claimId}
```

範例：

```http
GET /internal/cs/referrals/claim/12345
X-Internal-Token: <內部 token>
```

請檢查回傳內容中的 `claim` 和 `rewardLedger`。

### 8.1 客服怎麼知道 Google Play defer 失敗

客服不是去 Google Play Console 判斷 defer 失敗，而是看 backend internal API 回傳的 `rewardLedger`。

如果 `rewardLedger` 裡最新的 Google Play defer 紀錄是：

```text
rewardChannel = GOOGLE_PLAY_DEFER
grantStatus = FAILED_FINAL
googleDeferStatus = FAILED_FINAL
```

意思是：系統已經嘗試 Google Play defer，而且結果是最終失敗。

這時候才可以進一步判斷是否人工補償。

如果是：

```text
rewardChannel = GOOGLE_PLAY_DEFER
grantStatus = FAILED_RETRYABLE
googleDeferStatus = FAILED_RETRYABLE
```

意思是：Google Play defer 暫時失敗，但系統還會自動重試。客服不要人工補償。

如果是：

```text
rewardChannel = GOOGLE_PLAY_DEFER
grantStatus = GOOGLE_DEFER_IN_PROGRESS
googleDeferStatus = IN_PROGRESS
```

意思是：Google Play defer 還在處理中。客服不要人工補償。

如果是：

```text
grantStatus = SUCCESS
```

意思是：獎勵已經成功，不要再補一次。

### 8.2 rewardLedger 範例

可以人工補償的範例：

```json
{
  "sourceType": "REFERRAL_SUCCESS",
  "sourceRefId": 12345,
  "grantStatus": "FAILED_FINAL",
  "rewardChannel": "GOOGLE_PLAY_DEFER",
  "googleDeferStatus": "FAILED_FINAL",
  "errorCode": "GOOGLE_PLAY_DEFER_HTTP_400",
  "daysAdded": 30,
  "oldPremiumUntil": "2026-08-01T00:00:00Z",
  "newPremiumUntil": null,
  "grantedAtUtc": "2026-07-10T09:00:00Z"
}
```

不能人工補償，等待系統重試的範例：

```json
{
  "sourceType": "REFERRAL_SUCCESS",
  "sourceRefId": 12345,
  "grantStatus": "FAILED_RETRYABLE",
  "rewardChannel": "GOOGLE_PLAY_DEFER",
  "googleDeferStatus": "FAILED_RETRYABLE",
  "errorCode": "GOOGLE_PLAY_DEFER_HTTP_500",
  "daysAdded": 30,
  "nextRetryAtUtc": "2026-07-10T09:30:00Z"
}
```

已經補償成功的範例：

```json
{
  "sourceType": "REFERRAL_MANUAL_COMPENSATION",
  "sourceRefId": 12345,
  "grantStatus": "SUCCESS",
  "rewardChannel": "BACKEND_ONLY",
  "googleDeferStatus": "NOT_REQUIRED",
  "daysAdded": 30,
  "oldPremiumUntil": "2026-08-01T00:00:00Z",
  "newPremiumUntil": "2026-08-31T00:00:00Z"
}
```

## 9. Step 3：確認是否符合補償條件

在 `claim` 裡，建議看到以下狀態之一：

```text
status = REJECTED
rejectReason = REWARD_GRANT_FAILED
```

接著看 `rewardLedger`，需要找到最新一筆 Google Play defer 最終失敗紀錄。

必須符合：

```text
sourceType = REFERRAL_SUCCESS
rewardChannel = GOOGLE_PLAY_DEFER
grantStatus = FAILED_FINAL
googleDeferStatus = FAILED_FINAL
```

如果看到下面狀態，請不要補償：

```text
grantStatus = FAILED_RETRYABLE
googleDeferStatus = FAILED_RETRYABLE
```

原因：這代表系統還可能自動重試，人工補償太早介入可能造成重複或錯誤補償。

如果看到下面狀態，也請不要補償：

```text
grantStatus = SUCCESS
```

原因：代表獎勵已經成功，不應再補一次。

### 9.1 快速判斷表

| claim 狀態 | reward ledger 狀態 | 客服動作 |
|---|---|---|
| `PENDING_COOLDOWN` | 還沒有 defer ledger | 等待 7 天觀察期結束與排程處理 |
| `PROCESSING_REWARD` | `GOOGLE_DEFER_IN_PROGRESS` | 等待系統處理，不要人工補 |
| `REJECTED` + `REWARD_GRANT_FAILED` | `GOOGLE_PLAY_DEFER` + `FAILED_RETRYABLE` | 等待系統重試，不要人工補 |
| `REJECTED` + `REWARD_GRANT_FAILED` | `GOOGLE_PLAY_DEFER` + `FAILED_FINAL` | 可以評估人工補償 |
| `SUCCESS` | `SUCCESS` | 已發獎，不要再補 |
| `REJECTED` + `REFUNDED_OR_REVOKED` | 任意 | 退款或撤銷，不要補 |
| `REJECTED` + `CHARGEBACK` | 任意 | chargeback，不要補 |
| `REJECTED` + `ABUSE_RISK` | 任意 | 風控拒絕，不要補，升級主管或工程 |

客服只要記住：人工補償的核心條件是 `REJECTED + REWARD_GRANT_FAILED + GOOGLE_PLAY_DEFER + FAILED_FINAL`。

## 10. Step 4：執行人工補償

確認符合條件後，呼叫人工補償 API：

```http
POST /internal/cs/referrals/claim/{claimId}/manual-compensation
```

範例：

```http
POST /internal/cs/referrals/claim/12345/manual-compensation
X-Internal-Token: <內部 token>
```

此 API 不需要 request body。

成功時會回傳類似：

```json
{
  "claimId": 12345,
  "inviterUserId": 67890,
  "grantStatus": "SUCCESS",
  "rewardChannel": "BACKEND_ONLY",
  "sourceType": "REFERRAL_MANUAL_COMPENSATION",
  "oldPremiumUntil": "2026-08-01T00:00:00Z",
  "newPremiumUntil": "2026-08-31T00:00:00Z",
  "grantedAtUtc": "2026-07-10T09:00:00Z"
}
```

請注意：

- `oldPremiumUntil` 是補償前的 Premium 到期時間。
- `newPremiumUntil` 是補償後的 Premium 到期時間。
- 時間是 UTC，不一定是用戶當地時間。

## 11. Step 5：確認補償成功

補償後，請再次查詢 claim trace：

```http
GET /internal/cs/referrals/claim/{claimId}
```

請確認：

```text
claim.status = SUCCESS
claim.rewardedAtUtc 有值
```

並且 `rewardLedger` 中出現：

```text
sourceType = REFERRAL_MANUAL_COMPENSATION
grantStatus = SUCCESS
rewardChannel = BACKEND_ONLY
googleDeferStatus = NOT_REQUIRED
daysAdded = 30
```

也請確認：

```text
newPremiumUntil > oldPremiumUntil
```

如果原本用戶已收到「獎勵失敗」通知，系統會把該通知改成「獎勵成功」。如果 email outbox 支援發送成功通知，系統也會依現有規則建立成功通知信件。

## 12. 常見錯誤與處理方式

### 12.1 `REFERRAL_CLAIM_NOT_FOUND`

意思：找不到這個 claim ID。

處理方式：

1. 確認 claim ID 是否輸入錯誤。
2. 用 inviter user ID 重新查一次 `/internal/cs/referrals/inviter/{userId}`。
3. 如果仍找不到，升級工程協助。

### 12.2 `GOOGLE_DEFER_FINAL_FAILURE_REQUIRED`

意思：目前不符合人工補償條件。

常見原因：

- Google defer 還不是最終失敗。
- 還在 retryable 狀態。
- 還在 in progress 狀態。
- 已經成功發獎。
- 這個 claim 沒有 Google Play defer 失敗紀錄。

處理方式：

1. 回到 Step 3 重新確認 ledger。
2. 如果是 `FAILED_RETRYABLE`，等待系統自動重試。
3. 如果狀態看起來不合理，升級工程協助。

### 12.3 401 / 403 / internal token invalid

意思：內部 API token 錯誤、過期或沒有權限。

處理方式：

1. 確認你使用的是正確環境的 token。
2. 不要把 token 貼到工單。
3. 請主管或工程確認權限。

### 12.4 補償成功但用戶 App 還沒看到 Premium

可能原因：

- App 端會員狀態還沒刷新。
- 用戶網路快取或登入狀態尚未更新。
- 用戶登入的是另一個帳號。

請請用戶依序嘗試：

1. 關閉 App 後重新開啟。
2. 確認登入帳號是否正確。
3. 到會員頁或設定頁重新整理會員狀態。
4. 如果仍無效，提供 user ID 和 claim ID 給工程查詢。

## 13. 工單內部備註範本

請在工單內部備註留下：

```text
已確認 referral reward Google Play defer 最終失敗。
claimId: <claimId>
inviterUserId: <inviterUserId>
補償方式: REFERRAL_MANUAL_COMPENSATION
補償天數: 30 days
oldPremiumUntil: <oldPremiumUntil>
newPremiumUntil: <newPremiumUntil>
grantedAtUtc: <grantedAtUtc>
操作人員: <你的名字或客服代號>
```

不要在內部備註貼：

- 內部 API token。
- raw purchase token。
- 完整 Google Play purchase token。
- 不必要的用戶敏感資料。

## 14. 回覆用戶範本

### 14.1 補償成功

```text
您好，我們已協助確認這次邀請獎勵，並已在 App 內為您的 Premium 權益補上 30 天。

請重新開啟 App，或到會員頁重新整理狀態後確認。若仍未看到更新，請回覆我們，我們會再協助您確認。
```

### 14.2 還在系統重試中，暫時不能人工補償

```text
您好，我們已查到您的邀請獎勵正在系統處理中，目前仍在自動重試階段。

為了避免重複補償或造成訂閱狀態錯誤，我們會先等待系統完成處理。如果處理最終失敗，我們會再協助您人工補償。
```

### 14.3 不符合推薦獎勵資格

```text
您好，我們已協助確認這次邀請紀錄，但目前這筆邀請未符合獎勵資格，因此無法發放 30 天 Premium。

如果您認為結果有誤，請提供朋友完成訂閱的時間與帳號資訊，我們會再協助確認。
```

## 15. 不可以對用戶這樣說

請不要說：

```text
我們已經幫你延後 Google Play 扣款日期。
```

原因：人工補償只補 App 內 Premium 權益，沒有修改 Google Play 訂閱合約。

請改說：

```text
我們已在 App 內為你的 Premium 權益補上 30 天。
```

## 16. 什麼情況要升級工程

遇到以下情況，請不要自行補償，直接升級工程：

- claim ID 找不到。
- inviterUserId 和提出問題的用戶不一致。
- reward ledger 資料互相矛盾。
- `FAILED_RETRYABLE` 超過合理時間仍未變成成功或最終失敗。
- 補償 API 回傳錯誤，但客服無法判斷原因。
- 補償成功後，membership summary 仍顯示 FREE。
- 用戶聲稱被扣款、退款或 Google Play 訂單異常。
- 疑似濫用邀請碼或大量異常邀請。

升級工程時，請提供：

```text
userId:
claimId:
目前 claim.status:
目前 claim.rejectReason:
最新 rewardLedger grantStatus:
最新 rewardLedger rewardChannel:
最新 rewardLedger googleDeferStatus:
客服已操作過的步驟:
用戶回報內容:
```

## 17. 給主管的檢查清單

客服完成補償後，主管抽查時請確認：

- 是否真的有 `GOOGLE_PLAY_DEFER + FAILED_FINAL`。
- 是否只有補償一次。
- 是否有 `REFERRAL_MANUAL_COMPENSATION` ledger。
- 是否有 30 天補償紀錄。
- 工單是否沒有外洩 token 或敏感資料。
- 客服是否沒有承諾 Google Play 續訂日被延後。

## 18. 技術對照表

| 欄位 | 客服理解 |
|---|---|
| `claimId` | 這次邀請獎勵案件的 ID |
| `inviterUserId` | 應該拿到 30 天 Premium 的用戶 |
| `inviteeUserId` | 被邀請的朋友 |
| `REFERRAL_SUCCESS` | 系統原本的推薦獎勵流程 |
| `REFERRAL_MANUAL_COMPENSATION` | 客服人工補償流程 |
| `GOOGLE_PLAY_DEFER` | 嘗試延後 Google Play 訂閱到期日 |
| `BACKEND_ONLY` | 只補 App 內 Premium 權益 |
| `FAILED_RETRYABLE` | 還可能自動重試，先不要人工補 |
| `FAILED_FINAL` | 已經最終失敗，可以評估人工補 |
| `SUCCESS` | 已成功發放 |
| `oldPremiumUntil` | 補償前 Premium 到期時間 |
| `newPremiumUntil` | 補償後 Premium 到期時間 |

## 19. 最重要的三句話

1. 只有 `GOOGLE_PLAY_DEFER + FAILED_FINAL` 才能人工補償。
2. 人工補償只補 App 內 Premium，不會改 Google Play 扣款日。
3. 不確定時不要補，先升級工程或主管。
