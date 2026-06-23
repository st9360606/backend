CREATE TABLE IF NOT EXISTS referral_risk_signals
(
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    claim_id                 BIGINT      NOT NULL,
    device_hash              CHAR(64)    NULL,
    ip_hash                  CHAR(64)    NULL,
    payment_fingerprint_hash CHAR(64)    NULL,
    risk_score               INT         NOT NULL,
    risk_flags_json          JSON        NULL,
    decision                 VARCHAR(16) NOT NULL,
    created_at_utc           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_referral_risk_claim (claim_id, created_at_utc)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
