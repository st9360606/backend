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
