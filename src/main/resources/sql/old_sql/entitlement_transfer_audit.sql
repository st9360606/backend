CREATE TABLE IF NOT EXISTS entitlement_transfer_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    purchase_token_hash CHAR(64) NOT NULL,
    old_user_id BIGINT NOT NULL,
    new_user_id BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    product_id VARCHAR(128) NULL,
    entitlement_type VARCHAR(16) NULL,
    google_subscription_state VARCHAR(64) NULL,
    valid_to_utc DATETIME(6) NULL,
    transferred_at_utc DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_entitlement_transfer_token (purchase_token_hash),
    INDEX idx_entitlement_transfer_old_user (old_user_id),
    INDEX idx_entitlement_transfer_new_user (new_user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
