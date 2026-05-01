-- === user_entitlements（Google Play entitlement / subscription）===
-- Fresh DB 初始化用。
-- 已有資料的 DB 不要直接執行 DROP TABLE，請改跑 migration。

DROP TABLE IF EXISTS user_entitlements;

CREATE TABLE IF NOT EXISTS user_entitlements
(
    id                         CHAR(36)      NOT NULL,
    user_id                    BIGINT        NOT NULL,

    -- TRIAL / MONTHLY / YEARLY / REFERRAL_REWARD
    entitlement_type           VARCHAR(32)   NOT NULL,

    -- ACTIVE / EXPIRED / CANCELLED / REVOKED
    status                     VARCHAR(16)   NOT NULL,

    valid_from_utc             DATETIME(6)   NOT NULL,
    valid_to_utc               DATETIME(6)   NOT NULL,

    -- Google Play token hash for lookup / dedupe.
    purchase_token_hash        CHAR(64)      NULL,

    -- Encrypted raw Google Play purchase token.
    -- Required for backend-side subscriptionsv2.defer and periodic re-verification.
    purchase_token_ciphertext  VARCHAR(2048) NULL,

    last_verified_at_utc       DATETIME(6)   NULL,
    last_google_verified_at_utc DATETIME(6)  NULL,

    -- GOOGLE_PLAY / INTERNAL / DEV / REFERRAL_REWARD
    source                     VARCHAR(32)   NOT NULL DEFAULT 'INTERNAL',

    product_id                 VARCHAR(128)  NULL,
    subscription_state         VARCHAR(64)   NULL,

    -- OK / GRACE / ON_HOLD / EXPIRED / REVOKED / PENDING / PENDING_PURCHASE_CANCELED / UNKNOWN / PAUSED
    payment_state              VARCHAR(32)   NULL,

    grace_until_utc            DATETIME(6)   NULL,
    close_reason               VARCHAR(64)   NULL,

    -- FREE_TRIAL / INTRODUCTORY / BASE / PRORATION / UNKNOWN
    offer_phase                VARCHAR(32)   NULL,

    auto_renew_enabled         TINYINT(1)    NULL,
    acknowledgement_state      VARCHAR(64)   NULL,
    latest_order_id            VARCHAR(128)  NULL,
    linked_purchase_token_hash CHAR(64)      NULL,
    last_rtdn_at_utc           DATETIME(6)   NULL,
    revoked_at_utc             DATETIME(6)   NULL,

    created_at_utc             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY uk_entitlements_purchase_token_hash (purchase_token_hash),

    INDEX idx_entitlements_user (user_id, status, valid_to_utc),
    INDEX idx_entitlements_linked_purchase_token_hash (linked_purchase_token_hash),
    INDEX idx_entitlements_source_state (source, subscription_state),
    INDEX idx_entitlements_reverify (source, status, last_google_verified_at_utc),
    INDEX idx_entitlements_payment_state (payment_state, status, valid_to_utc)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
