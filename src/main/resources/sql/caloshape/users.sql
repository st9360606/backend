-- ============================================================
-- Table: users
-- Purpose:
-- 1. Store app user identity records.
-- 2. Support Google / Email / Apple login.
-- 3. Support soft-deleted or anonymized account state.
-- 4. Allow email reuse after account deletion by clearing email
--    and preserving deleted_email_hash for audit/debug traceability.
-- ============================================================

CREATE TABLE IF NOT EXISTS users
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- Google subject. Nullable to support Email / Apple login.
    google_sub         VARCHAR(64)                     NULL,

    email              VARCHAR(255)                    NULL,

    -- Email login password hash.
    -- Should store BCrypt / Argon2 hash only, never plaintext.
    password_hash      VARCHAR(255)                    NULL,

    provider           ENUM ('GOOGLE','EMAIL','APPLE') NOT NULL DEFAULT 'EMAIL',

    -- ACTIVE / DELETED / DISABLED / BANNED
    status             VARCHAR(16)                     NOT NULL DEFAULT 'ACTIVE',

    deleted_at_utc     DATETIME(6)                     NULL,
    deleted_email_hash CHAR(64)                        NULL,

    email_verified     TINYINT(1)                      NOT NULL DEFAULT 0,

    name               VARCHAR(255)                    NULL,
    picture            VARCHAR(512)                    NULL,

    created_at         TIMESTAMP                       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP                       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_login_at      TIMESTAMP                       NULL,

    UNIQUE KEY uq_users_email
        (email),

    INDEX idx_users_status
        (status)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
