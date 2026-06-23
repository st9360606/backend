# 資料刪除政策與流程（v1）

## 1) 單筆刪除
使用者可在 App 內刪除單筆飲食紀錄。刪除後：
- 伺服器會標記該筆紀錄為刪除狀態
- 系統會排程刪除對應照片與衍生資料

## 2) 自動刪除（Retention）
- DRAFT / PENDING / FAILED：最多保留 3 天，之後自動刪除照片與結果
- SAVED：最多保留 32 天，之後自動刪除照片、結果與歷史紀錄

## 3) 帳號刪除
使用者可在 App 內提出刪除帳號請求：

`Settings → Delete Account → Delete`

帳號刪除分成兩階段：
1. 立即鎖定與去識別化：移除 email、Google profile identifier、顯示名稱、頭像等識別資訊，並撤銷登入 token。
2. 背景批次清除：分批刪除 App 相關資料，例如飲食照片、營養結果、個人資料、活動摘要、飲水資料、運動資料、體重紀錄、通知、AI quota、推薦碼與登入 token。

## 4) Google Play 訂閱與免費試用
如果使用者透過 Google Play 購買訂閱或啟用免費試用，該訂閱由 Google Play 管理。

刪除 CaloShape 帳號會刪除或去識別化 App 帳號與相關 App 資料，但不一定會自動取消仍有效的 Google Play 訂閱或免費試用。使用者刪除帳號前，應先到 Google Play 管理或取消訂閱：

`Google Play → Payments & subscriptions → Subscriptions`

基於帳務、退款、拒付、防詐、客服、稅務與法遵需求，系統可能會保留最小化的訂閱紀錄一段合理期間。這些保留資料不會用來重建已刪除的 App 帳號。

## 5) 重新註冊
使用者刪除帳號後，如果未來使用相同 email 重新註冊，會被視為新的帳號與新的 user ID。Google Play 訂閱能否恢復，仍以 Google Play 驗證結果為準。

## 6) 聯絡方式
Email：support@viraldevelopment.co
