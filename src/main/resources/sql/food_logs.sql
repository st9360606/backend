-- MySQL 8.x
-- 你說「已建但沒資料」：直接 drop + recreate 最乾淨
DROP TABLE IF EXISTS food_logs;

CREATE TABLE food_logs
(
    id                     CHAR(36)                                            NOT NULL,
    user_id                BIGINT                                              NOT NULL,
    status                 ENUM ('PENDING','DRAFT','SAVED','FAILED','DELETED') NOT NULL,
    method                 VARCHAR(16)                                         NOT NULL, -- PHOTO/ALBUM/BARCODE/LABEL
    provider               VARCHAR(32)                                         NOT NULL, -- STUB/LOGMEAL/...
    degrade_level          VARCHAR(8)                                          NULL,     -- DG-0..DG-4

    -- time (一律以 UTC 寫入到 DATETIME)
    captured_at_utc        DATETIME(6)                                         NOT NULL,
    captured_tz            VARCHAR(64)                                         NOT NULL, -- IANA
    captured_local_date    DATE                                                NOT NULL, -- for summary
    server_received_at_utc DATETIME(6)                                         NOT NULL,

    time_source            ENUM ('EXIF','DEVICE_CLOCK','SERVER_RECEIVED')      NOT NULL,
    time_suspect           BOOLEAN                                             NOT NULL DEFAULT FALSE,

    -- input refs
    image_object_key       TEXT                                                NULL,
    image_sha256           CHAR(64)                                            NULL,
    barcode                VARCHAR(64)                                         NULL,

    -- effective values（列表/彙總以此為準）
    effective              JSON                                                NULL,

    -- original snapshot（Step2 再做表/ref）
    original_snapshot_ref  CHAR(36)                                            NULL,

    -- error / deleted
    last_error_code        VARCHAR(64)                                         NULL,
    last_error_message     TEXT                                                NULL,
    deleted_at_utc         DATETIME(6)                                         NULL,
    deleted_by             VARCHAR(16)                                         NULL,     -- USER/SYSTEM/ADMIN

    created_at_utc         DATETIME(6)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc         DATETIME(6)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    INDEX idx_food_logs_user_date (user_id, captured_local_date),
    INDEX idx_food_logs_user_status (user_id, status),
    INDEX idx_food_logs_sha256 (user_id, image_sha256)
    -- ✅ 可選：如果你確定 users(id) 存在且想強約束
    -- ,CONSTRAINT fk_food_logs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE food_logs
    ADD COLUMN image_content_type VARCHAR(64) NULL AFTER image_sha256,
    ADD COLUMN image_size_bytes BIGINT NULL AFTER image_content_type;

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


-- === food_log_overrides（回溯覆寫）===
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


-- === usage_counters（配額：server_now + user_tz 的 local_date）===
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


-- === user_entitlements（訂閱/試用）===
CREATE TABLE IF NOT EXISTS user_entitlements
(
    id                   CHAR(36)    NOT NULL,
    user_id              BIGINT      NOT NULL,
    entitlement_type     VARCHAR(16) NOT NULL, -- TRIAL/MONTHLY/YEARLY
    status               VARCHAR(16) NOT NULL, -- ACTIVE/EXPIRED/CANCELLED
    valid_from_utc       DATETIME(6) NOT NULL,
    valid_to_utc         DATETIME(6) NOT NULL,

    purchase_token_hash  CHAR(64)    NULL,
    last_verified_at_utc DATETIME(6) NULL,

    created_at_utc       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    INDEX idx_entitlements_user (user_id, status, valid_to_utc)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;


-- === deletion_jobs（刪圖/刪 payload 任務）===
DROP TABLE IF EXISTS deletion_jobs;

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



CREATE TABLE IF NOT EXISTS food_log_requests
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,  -- 你 RequestIdFilter 產生的 UUID 字串
    food_log_id     CHAR(36)     NULL,      -- attach 後才有
    status          VARCHAR(16)  NOT NULL,  -- RESERVED/ATTACHED/FAILED
    error_code      VARCHAR(64)  NULL,
    error_message   TEXT         NULL,

    created_at_utc  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_food_log_requests_user_req (user_id, request_id),
    INDEX idx_food_log_requests_log (food_log_id)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE IF NOT EXISTS image_blobs
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    sha256          CHAR(64)    NOT NULL,

    object_key      TEXT        NOT NULL,    -- user-<uid>/blobs/sha256/<sha>.jpg
    content_type    VARCHAR(64) NOT NULL,
    size_bytes      BIGINT      NOT NULL,
    ext             VARCHAR(8)  NOT NULL,    -- .jpg/.png

    ref_count       INT         NOT NULL DEFAULT 1,

    created_at_utc  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_image_blobs_user_sha (user_id, sha256),
    INDEX idx_image_blobs_user (user_id)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

