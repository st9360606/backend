CREATE TABLE IF NOT EXISTS email_login_codes (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email        VARCHAR(255)   NOT NULL,
    code_hash    CHAR(64)       NOT NULL,                  -- sha256(OTP) → 64位十六進位
    purpose      VARCHAR(32)    NOT NULL DEFAULT 'LOGIN',  -- 之後要擴充可改 ENUM
    expires_at   DATETIME       NOT NULL,                  -- 到期時間（UTC 建議）
    consumed_at  DATETIME       NULL,                      -- 使用時間（已用則非空）
    created_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 建立時間（DB自帶）
    attempt_cnt  INT            NOT NULL DEFAULT 0,        -- 嘗試次數
    client_ip    VARCHAR(45)    NULL,                      -- IPv6 最長 45
    user_agent   VARCHAR(255)   NULL                       -- UA 字串
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 依 email 取最近一筆（你有 findLatestActive）
CREATE INDEX idx_elc_email_created ON email_login_codes (email, created_at DESC);

-- 查有效碼（email + 未過期 + 未使用）
CREATE INDEX idx_elc_active ON email_login_codes (email, expires_at, consumed_at);

-- 清理過期資料
CREATE INDEX idx_elc_expires_at ON email_login_codes (expires_at);
