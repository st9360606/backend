-- ============================================================
-- Table: user_notifications
-- Purpose:
-- 1. Store user-facing notifications.
-- 2. Prevent duplicate notifications from the same business source.
-- 3. Support notification inbox ordering by user and created time.
-- ============================================================

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

    UNIQUE KEY ux_user_notifications_source
        (user_id, source_type, source_ref_id),

    INDEX idx_user_notifications_user_created
        (user_id, created_at_utc)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
