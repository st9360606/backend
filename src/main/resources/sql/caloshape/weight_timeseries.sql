-- ============================================================
-- Table: weight_timeseries
-- Purpose:
-- 1. Store user weight time-series data by local date.
-- 2. Support weight progress chart.
-- 3. Store both metric and imperial values.
-- 4. Ensure one weight record per user per local date.
-- ============================================================

CREATE TABLE IF NOT EXISTS weight_timeseries
(
    id         BIGINT        NOT NULL AUTO_INCREMENT,

    user_id    BIGINT        NOT NULL,

    -- User-local date for weight charting.
    log_date   DATE          NOT NULL,

    weight_kg  DECIMAL(6, 1) NOT NULL,
    weight_lbs DECIMAL(6, 1) NOT NULL,

    timezone   VARCHAR(64)   NOT NULL,

    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT uq_weight_timeseries_user_date
        UNIQUE (user_id, log_date)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


CREATE OR REPLACE VIEW v_weight_timeseries AS
SELECT id,
       user_id,
       log_date,
       weight_kg,
       weight_lbs,
       timezone,
       created_at
FROM weight_timeseries;
