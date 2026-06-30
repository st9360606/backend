CREATE TABLE user_daily_workout_summary
(
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT        NOT NULL,
    local_date               DATE          NOT NULL,
    timezone                 VARCHAR(64)   NOT NULL DEFAULT 'UTC',

    workout_kcal             DECIMAL(12,3) NOT NULL DEFAULT 0,
    activity_kcal            DECIMAL(12,3) NOT NULL DEFAULT 0,
    total_burned_kcal        DECIMAL(12,3) NOT NULL DEFAULT 0,
    workout_session_count    INT           NOT NULL DEFAULT 0,

    last_recomputed_at_utc   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at_utc           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_daily_workout_summary_user_date (user_id, local_date),
    KEY idx_user_daily_workout_summary_user_date (user_id, local_date),
    KEY idx_user_daily_workout_summary_date (local_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;