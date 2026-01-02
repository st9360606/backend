-- =========================================================
-- user_daily_activity (merged)
-- 목표:
-- 1) total_kcal -> active_kcal（nullable）
-- 2) steps 改 nullable
-- 3) source -> ingest_source（default HEALTH_CONNECT）
-- 4) 新增 data_origin_package / data_origin_name
-- 5) retention indexes
-- =========================================================

CREATE TABLE IF NOT EXISTS user_daily_activity
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id            BIGINT      NOT NULL,
    local_date         DATE        NOT NULL,
    timezone           VARCHAR(64) NOT NULL,
    day_start_utc      TIMESTAMP   NOT NULL,
    day_end_utc        TIMESTAMP   NOT NULL,
    -- (2) steps nullable
    steps              BIGINT      NULL,
    -- (1) total_kcal -> active_kcal nullable
    active_kcal        DOUBLE      NULL,
    -- (3) source -> ingest_source
    ingest_source      VARCHAR(32) NOT NULL DEFAULT 'HEALTH_CONNECT',
    -- (4) origin info
    data_origin_package VARCHAR(255) NULL,
    data_origin_name    VARCHAR(255) NULL,
    -- (4) ingested timestamp (你要 AFTER data_origin_name)
    ingested_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_user_day (user_id, local_date),
    KEY idx_user_daily_activity_user_date (user_id, local_date)
    );

-- (5) retention indexes
CREATE INDEX idx_user_day_end_utc ON user_daily_activity(user_id, day_end_utc);
CREATE INDEX idx_day_end_utc ON user_daily_activity(day_end_utc);
