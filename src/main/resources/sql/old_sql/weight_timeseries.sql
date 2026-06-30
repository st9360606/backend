CREATE TABLE IF NOT EXISTS weight_timeseries
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT        NOT NULL,
    log_date   DATE          NOT NULL,
    weight_kg  DECIMAL(6, 1) NOT NULL,
    weight_lbs DECIMAL(6, 1) NOT NULL,
    timezone   VARCHAR(64)   NOT NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_weight_timeseries_user_date UNIQUE (user_id, log_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


CREATE VIEW v_weight_timeseries AS
SELECT id,
       user_id,
       log_date,
       weight_kg,
       weight_lbs,
       timezone,
       created_at
FROM weight_timeseries;


CREATE INDEX idx_wt_user_date ON weight_timeseries (user_id, log_date);
