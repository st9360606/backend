-- === user_entitlements（Google Play entitlement / subscription）===
-- 注意：
-- 這份是 fresh DB 初始化用。
-- 已有資料的 DB 不要直接執行 DROP TABLE。

DROP TABLE IF EXISTS user_entitlements;

CREATE TABLE IF NOT EXISTS user_entitlements
(
    id                         CHAR(36)     NOT NULL,
    user_id                    BIGINT       NOT NULL,

    entitlement_type           VARCHAR(16)  NOT NULL, -- TRIAL / MONTHLY / YEARLY
    status                     VARCHAR(16)  NOT NULL, -- ACTIVE / EXPIRED / CANCELLED / REVOKED

    valid_from_utc             DATETIME(6)  NOT NULL,
    valid_to_utc               DATETIME(6)  NOT NULL,

    purchase_token_hash        CHAR(64)     NULL,
    last_verified_at_utc       DATETIME(6)  NULL,

    source                     VARCHAR(32)  NOT NULL DEFAULT 'INTERNAL', -- GOOGLE_PLAY / INTERNAL / DEV
    product_id                 VARCHAR(128) NULL,
    subscription_state         VARCHAR(64)  NULL,
    offer_phase                VARCHAR(32)  NULL, -- FREE_TRIAL / INTRODUCTORY / BASE / PRORATION / UNKNOWN
    auto_renew_enabled         TINYINT(1)   NULL,
    acknowledgement_state      VARCHAR(64)  NULL,
    latest_order_id            VARCHAR(128) NULL,
    linked_purchase_token_hash CHAR(64)     NULL,
    last_rtdn_at_utc           DATETIME(6)  NULL,
    revoked_at_utc             DATETIME(6)  NULL,

    created_at_utc             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY uk_entitlements_purchase_token_hash (purchase_token_hash),

    INDEX idx_entitlements_user (user_id, status, valid_to_utc),
    INDEX idx_entitlements_linked_purchase_token_hash (linked_purchase_token_hash),
    INDEX idx_entitlements_source_state (source, subscription_state)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
