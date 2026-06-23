-- === food_log_overrides（回溯覆寫）===
DROP TABLE IF EXISTS food_log_overrides;
CREATE TABLE IF NOT EXISTS food_log_overrides
(
    id             CHAR(36)    NOT NULL,
    food_log_id    CHAR(36)    NOT NULL,

    field_key      VARCHAR(32) NOT NULL, -- FOOD_NAME/QUANTITY/NUTRIENTS/HEALTH_SCORE...
    old_value_json JSON        NULL,
    new_value_json JSON        NOT NULL,

    editor_type    VARCHAR(16) NOT NULL, -- USER/ADMIN/SYSTEM
    reason         TEXT        NULL,
    edited_at_utc  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    INDEX idx_food_log_overrides_log (food_log_id, edited_at_utc),

    CONSTRAINT fk_food_log_overrides_food_log
        FOREIGN KEY (food_log_id) REFERENCES food_logs (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
