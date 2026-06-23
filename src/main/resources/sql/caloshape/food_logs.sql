-- ============================================================
-- Table: food_logs
-- Purpose:
-- 1. Store user food recognition / barcode / label log records.
-- 2. Preserve captured time in UTC with timezone metadata.
-- 3. Support draft, saved, failed, deleted lifecycle.
-- 4. Store effective nutrition values used by list/detail/summary.
-- 5. Store base_effective + portion_multiplier for quantity adjustment.
-- ============================================================

CREATE TABLE IF NOT EXISTS food_logs
(
    id                     CHAR(36)                                            NOT NULL,
    user_id                BIGINT                                              NOT NULL,

    status                 ENUM ('PENDING','DRAFT','SAVED','FAILED','DELETED') NOT NULL,
    method                 VARCHAR(16)                                         NOT NULL COMMENT 'PHOTO/ALBUM/BARCODE/LABEL',
    provider               VARCHAR(32)                                         NOT NULL COMMENT 'STUB/LOGMEAL/...',
    degrade_level          VARCHAR(8)                                          NULL COMMENT 'DG-0..DG-4',

    -- Time fields.
    -- All UTC values are written into DATETIME(6).
    captured_at_utc        DATETIME(6)                                         NOT NULL,
    captured_tz            VARCHAR(64)                                         NOT NULL COMMENT 'IANA timezone',
    captured_local_date    DATE                                                NOT NULL COMMENT 'User local date for daily summary',
    server_received_at_utc DATETIME(6)                                         NOT NULL,

    time_source            ENUM ('EXIF','DEVICE_CLOCK','SERVER_RECEIVED')      NOT NULL,
    time_suspect           BOOLEAN                                             NOT NULL DEFAULT FALSE,

    -- Input references.
    image_object_key       TEXT                                                NULL,
    image_sha256           CHAR(64)                                            NULL,
    image_content_type     VARCHAR(64)                                         NULL,
    image_size_bytes       BIGINT                                              NULL,
    barcode                VARCHAR(64)                                         NULL,

    -- Effective values used by list/detail/summary.
    effective              JSON                                                NULL,

    -- Base effective values before portion adjustment.
    base_effective         JSON                                                NULL,

    -- Portion multiplier applied to base_effective.
    portion_multiplier     INT                                                 NOT NULL DEFAULT 1,

    -- Original AI/provider snapshot.
    original_snapshot_ref  CHAR(36)                                            NULL,

    -- Error / deleted metadata.
    last_error_code        VARCHAR(64)                                         NULL,
    last_error_message     TEXT                                                NULL,
    deleted_at_utc         DATETIME(6)                                         NULL,
    deleted_by             VARCHAR(16)                                         NULL COMMENT 'USER/SYSTEM/ADMIN',

    created_at_utc         DATETIME(6)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc         DATETIME(6)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Explicit favorite timestamp for saved-food ordering.
    saved_at_utc           DATETIME(6)                                         NULL,

    PRIMARY KEY (id),

    -- History list:
    -- user + status + local date range + captured time sorting.
    INDEX idx_food_logs_user_status_date
        (user_id, status, captured_local_date, captured_at_utc),

    -- Image dedupe lookup:
    -- user + image hash + status + latest created record.
    INDEX idx_food_logs_user_sha_status_created
        (user_id, image_sha256, status, created_at_utc),

    -- Saved foods ordering:
    -- user + SAVED status + saved timestamp.
    INDEX idx_food_logs_user_status_saved_at
        (user_id, status, saved_at_utc),

    -- Worker / retry scan:
    -- status + server received time.
    INDEX idx_food_logs_status_received
        (status, server_received_at_utc)

    -- Optional:
    -- Enable only if users(id) already exists and you want hard FK constraint.
    -- ,CONSTRAINT fk_food_logs_user
    --     FOREIGN KEY (user_id) REFERENCES users(id)
    --     ON DELETE CASCADE

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
