CREATE TABLE IF NOT EXISTS weight_history
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT        NOT NULL,
    log_date   DATE          NOT NULL,
    weight_kg  DECIMAL(6, 1) NOT NULL,
    weight_lbs DECIMAL(6, 1) NOT NULL,
    timezone   VARCHAR(64)   NOT NULL,
    photo_url  TEXT          NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_weight_history_user_date UNIQUE (user_id, log_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


DROP VIEW IF EXISTS v_weight_history;
CREATE VIEW v_weight_history AS
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

CREATE INDEX idx_wh_user_date ON weight_history (user_id, log_date);
