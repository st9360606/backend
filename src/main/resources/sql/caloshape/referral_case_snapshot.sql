CREATE TABLE IF NOT EXISTS referral_case_snapshot
(
    id                         BIGINT      NOT NULL AUTO_INCREMENT,
    inviter_user_id            BIGINT      NOT NULL,
    total_invited              INT         NOT NULL DEFAULT 0,
    success_count              INT         NOT NULL DEFAULT 0,
    rejected_count             INT         NOT NULL DEFAULT 0,
    pending_verification_count INT         NOT NULL DEFAULT 0,
    total_rewarded_days        INT         NOT NULL DEFAULT 0,
    current_premium_until      DATETIME(6) NULL,
    updated_at_utc             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_referral_case_snapshot_user (inviter_user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
