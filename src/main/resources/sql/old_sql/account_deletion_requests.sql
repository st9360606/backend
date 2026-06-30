CREATE TABLE IF NOT EXISTS account_deletion_requests (
    id               CHAR(36) NOT NULL,
    user_id          BIGINT   NOT NULL,
    req_status       VARCHAR(16) NOT NULL, -- REQUESTED/RUNNING/DONE/FAILED
    requested_at_utc DATETIME(6) NOT NULL,
    started_at_utc   DATETIME(6) NULL,
    completed_at_utc DATETIME(6) NULL,
    subscription_warning_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    user_requested_google_play_cancel BOOLEAN NOT NULL DEFAULT FALSE,
    has_active_google_play_subscription_at_request BOOLEAN NOT NULL DEFAULT FALSE,
    active_entitlement_type_at_request VARCHAR(16) NULL,
    active_entitlement_source_at_request VARCHAR(32) NULL,
    active_product_id_at_request VARCHAR(128) NULL,
    active_valid_to_utc_at_request DATETIME(6) NULL,
    attempts         INT NOT NULL DEFAULT 0,
    next_retry_at_utc DATETIME(6) NULL,
    last_error       TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_del_user (user_id),
    INDEX idx_account_del_status (req_status, next_retry_at_utc)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
