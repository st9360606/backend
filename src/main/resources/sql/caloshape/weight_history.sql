-- ============================================================
-- Table: weight_history
-- Purpose:
-- 1. Store user weight history by local date.
-- 2. Support metric and imperial display.
-- 3. Support optional photo record.
-- 4. Ensure one weight record per user per local date.
-- ============================================================

CREATE TABLE IF NOT EXISTS weight_history
(
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    user_id    BIGINT        NOT NULL,

    -- User-local date for weight logging.
    log_date   DATE          NOT NULL,

    weight_kg  DECIMAL(6, 1) NOT NULL,
    weight_lbs DECIMAL(6, 1) NOT NULL,

    timezone   VARCHAR(64)   NOT NULL,

    photo_url  TEXT          NULL,

    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT uq_weight_history_user_date
        UNIQUE (user_id, log_date)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


CREATE OR REPLACE VIEW v_weight_history AS
SELECT id,
       user_id,
       log_date,
       weight_kg,
       weight_lbs,
       timezone,
       photo_url,
       created_at,
       updated_at
FROM weight_history;
