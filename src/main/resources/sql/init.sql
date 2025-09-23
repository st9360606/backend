-- V1__init_schema.sql

-- 使用者
CREATE TABLE IF NOT EXISTS users
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    google_sub     VARCHAR(64)                     NULL, -- 放寬為可空：支援 Email/Apple 登入
    email          VARCHAR(255)                    NULL,
    password_hash  VARCHAR(255)                    NULL, -- Email 登入用（Bcrypt/Argon2）
    provider       ENUM ('GOOGLE','EMAIL','APPLE') NOT NULL DEFAULT 'EMAIL',
    email_verified TINYINT(1)                      NOT NULL DEFAULT 0,
    name           VARCHAR(255),
    picture        VARCHAR(512),
    created_at     TIMESTAMP                       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP                       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_login_at  TIMESTAMP                       NULL,
    UNIQUE KEY uq_users_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Token（Access/Refresh；不透明 32 bytes → hex 64）
CREATE TABLE IF NOT EXISTS auth_tokens
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    token       CHAR(64)                  NOT NULL UNIQUE,
    user_id     BIGINT                    NOT NULL,
    type        ENUM ('ACCESS','REFRESH') NOT NULL,
    expires_at  TIMESTAMP                 NOT NULL,
    created_at  TIMESTAMP                 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked     TINYINT(1)                NOT NULL DEFAULT 0,
    replaced_by CHAR(64)                  NULL, -- refresh 旋轉時指向新 token
    device_id   VARCHAR(128)              NULL,
    client_ip   VARCHAR(64)               NULL,
    user_agent  VARCHAR(255)              NULL,
    CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 索引
CREATE INDEX idx_auth_tokens_user ON auth_tokens (user_id);
CREATE INDEX idx_auth_tokens_expires ON auth_tokens (expires_at);


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
