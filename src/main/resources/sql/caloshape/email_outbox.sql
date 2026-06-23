-- ============================================================
-- Table: email_outbox
-- Purpose:
-- 1. Store pending email jobs before they are sent.
-- 2. Support retryable and idempotent email delivery.
-- 3. Prevent duplicate emails through dedupe_key.
-- 4. Allow querying email records by status and user.
-- ============================================================

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

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
