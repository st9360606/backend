-- === food_log_tasks（承接 PENDING）===
DROP TABLE IF EXISTS food_log_tasks;

CREATE TABLE food_log_tasks
(
    id                 CHAR(36)                                                   NOT NULL,
    food_log_id        CHAR(36)                                                   NOT NULL,

    task_status        ENUM ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED') NOT NULL,
    attempts           INT                                                        NOT NULL DEFAULT 0,
    next_retry_at_utc  DATETIME(6)                                                NULL,

    poll_after_sec     INT                                                        NOT NULL DEFAULT 2,

    last_error_code    VARCHAR(64)                                                NULL,
    last_error_message TEXT                                                       NULL,

    created_at_utc     DATETIME(6)                                                NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc     DATETIME(6)                                                NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    -- ✅ 一個 food_log 對應一個 task（Step2 MVP 先這樣）
    UNIQUE KEY ux_food_log_tasks_food_log_id (food_log_id),

    INDEX idx_food_log_tasks_status (task_status, next_retry_at_utc),

    CONSTRAINT fk_food_log_tasks_food_log
        FOREIGN KEY (food_log_id) REFERENCES food_logs (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
