CREATE TABLE IF NOT EXISTS account_deletion_requests (
    id               CHAR(36) NOT NULL,
    user_id          BIGINT   NOT NULL,
    req_status       VARCHAR(16) NOT NULL, -- REQUESTED/RUNNING/DONE/FAILED
    requested_at_utc DATETIME(6) NOT NULL,
    started_at_utc   DATETIME(6) NULL,
    completed_at_utc DATETIME(6) NULL,
    attempts         INT NOT NULL DEFAULT 0,
    next_retry_at_utc DATETIME(6) NULL,
    last_error       TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_del_user (user_id),
    INDEX idx_account_del_status (req_status, next_retry_at_utc)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
