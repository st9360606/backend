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
