CREATE TABLE barcode_lookup_cache
(
    barcode_norm        VARCHAR(32) NOT NULL,
    barcode_raw_example VARCHAR(64) NULL,
    status              VARCHAR(16) NOT NULL,
    provider            VARCHAR(32) NOT NULL,
    payload_text        LONGTEXT    NULL,
    expires_at_utc      DATETIME(3) NOT NULL,
    created_at_utc      DATETIME(3) NOT NULL,
    updated_at_utc      DATETIME(3) NOT NULL,
    PRIMARY KEY (barcode_norm),
    KEY idx_expires_at_utc (expires_at_utc)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
