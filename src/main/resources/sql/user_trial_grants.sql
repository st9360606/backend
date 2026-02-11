CREATE TABLE IF NOT EXISTS user_trial_grants
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    email_hash     CHAR(64)    NOT NULL,
    device_hash    CHAR(64)    NOT NULL,
    first_user_id  BIGINT      NOT NULL,
    granted_at_utc DATETIME(6) NOT NULL,
    UNIQUE KEY uq_trial_email (email_hash),
    UNIQUE KEY uq_trial_device (device_hash)
);
