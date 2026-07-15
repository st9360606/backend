-- === deletion_jobs（刪圖/刪 payload 任務）===
-- Production migrations must never drop this table; cleanup code owns row deletion.

CREATE TABLE IF NOT EXISTS deletion_jobs
(
    id                CHAR(36)    NOT NULL,
    food_log_id       CHAR(36)    NOT NULL,

    -- ✅ 不要靠 parse objectKey 推導：直接把必要欄位放進來
    user_id           BIGINT      NOT NULL,
    sha256            CHAR(64)    NULL,
    ext               VARCHAR(8)  NULL,

    job_status        VARCHAR(16) NOT NULL, -- QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED
    attempts          INT         NOT NULL DEFAULT 0,
    next_retry_at_utc DATETIME(6) NULL,

    image_object_key  TEXT        NULL,
    last_error        TEXT        NULL,

    created_at_utc    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    INDEX idx_deletion_jobs_food_log_id (food_log_id),
    INDEX idx_deletion_jobs_status (job_status, next_retry_at_utc),
    INDEX idx_deletion_jobs_user_sha (user_id, sha256),

    CONSTRAINT fk_deletion_jobs_food_log
        FOREIGN KEY (food_log_id) REFERENCES food_logs (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
