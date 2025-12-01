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