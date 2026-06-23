CREATE TABLE IF NOT EXISTS user_referral_codes
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    promo_code     VARCHAR(24) NOT NULL,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at_utc DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY ux_user_referral_codes_user (user_id),
    UNIQUE KEY ux_user_referral_codes_code (promo_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
