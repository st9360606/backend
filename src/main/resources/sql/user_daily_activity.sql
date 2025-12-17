CREATE TABLE IF NOT EXISTS user_daily_activity
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    local_date    DATE        NOT NULL,
    timezone      VARCHAR(64) NOT NULL,
    day_start_utc TIMESTAMP   NOT NULL,
    day_end_utc   TIMESTAMP   NOT NULL,
    steps         BIGINT      NOT NULL DEFAULT 0,
    total_kcal    DOUBLE      NOT NULL DEFAULT 0,
    source        VARCHAR(32) NOT NULL DEFAULT 'HEALTH_CONNECT',
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_user_day (user_id, local_date),
    KEY idx_user_daily_activity_user_date (user_id, local_date)
);
