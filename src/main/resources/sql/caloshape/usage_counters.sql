-- === usage_counters（配額：server_now + user_tz 的 local_date）===
DROP TABLE IF EXISTS usage_counters;
CREATE TABLE IF NOT EXISTS usage_counters
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    local_date     DATE        NOT NULL,
    used_count     INT         NOT NULL DEFAULT 0,
    updated_at_utc DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_counters_user_date (user_id, local_date),
    INDEX idx_usage_counters_user_date (user_id, local_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
