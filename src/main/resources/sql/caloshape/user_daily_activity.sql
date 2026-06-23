DROP TABLE IF EXISTS user_daily_activity;

CREATE TABLE user_daily_activity
(
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT      NOT NULL,
    local_date          DATE        NOT NULL,
    timezone            VARCHAR(64) NOT NULL,
    day_start_utc       TIMESTAMP   NOT NULL,
    day_end_utc         TIMESTAMP   NOT NULL,

    steps               BIGINT      NULL,
    active_kcal         DOUBLE      NULL,

    ingest_source       VARCHAR(32) NOT NULL DEFAULT 'HEALTH_CONNECT',
    data_origin_package VARCHAR(255) NULL,
    data_origin_name    VARCHAR(255) NULL,

    ingested_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_user_day (user_id, local_date),
    KEY idx_user_daily_activity_user_date (user_id, local_date),
    KEY idx_user_day_end_utc (user_id, day_end_utc),
    KEY idx_day_end_utc (day_end_utc)
);
