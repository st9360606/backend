# Google Play Console - Data Safety 對照表（v1）

> 目的：協助你在 Play Console 的 Data safety 表單正確勾選。
> 你仍需依你的實際 UI/功能（例如是否有體重、運動、訂閱等模組）補齊。

---

## A) 收集的資料類型（建議勾選方向）

### 1) Personal info（個人資訊）
- Email（如有帳號登入）
- User ID（內部識別）

**用途**：
- App 功能（登入、同步）
- 安全防護（防濫用）

### 2) Photos and videos（照片/影片）
- 食物照片（拍照/相簿上傳）

**用途**：
- App 功能（食物辨識）
- 除錯（必要時以最小化方式）

**分享第三方**：是（AI 辨識供應商，如 Google Gemini）

### 3) App activity（App 活動）
- 飲食記錄（食物名稱、營養素、信心分數、警示）
- 使用次數（配額/用量統計）

**用途**：
- App 功能（統計、歷史）
- 分析（彙總）

### 4) Device or other IDs（裝置或其他識別碼）
- 可能包含 requestId / 技術識別（不等同於廣告 ID）

**用途**：
- 除錯與安全

---

## B) 資料處理與分享（建議文字）

- Food photos are processed by a third-party AI service provider to generate nutrition estimates.
- We do not sell personal data.
- Users can delete individual entries and data is automatically deleted by retention policy.

---

## C) Retention（保存與刪除）

- DRAFT/PENDING/FAILED photos & results: auto-deleted after **3 days**
- SAVED photos & results (including history): auto-deleted after **32 days**

---

## D) Security practices（安全）

- Data transmitted over HTTPS
- Access control on backend services
- Retention & deletion jobs to minimize storage
