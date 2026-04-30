-- Existing DB migration for Google Play payment state hardening.
-- Run once on existing environments before deploying the updated backend.

ALTER TABLE user_entitlements
    ADD COLUMN purchase_token_ciphertext VARCHAR(2048) NULL AFTER purchase_token_hash,
    ADD COLUMN last_google_verified_at_utc DATETIME(6) NULL AFTER last_verified_at_utc,
    ADD COLUMN payment_state VARCHAR(32) NULL AFTER subscription_state,
    ADD COLUMN grace_until_utc DATETIME(6) NULL AFTER payment_state,
    ADD COLUMN close_reason VARCHAR(64) NULL AFTER grace_until_utc;

CREATE INDEX idx_entitlements_reverify
    ON user_entitlements (source, status, last_google_verified_at_utc);

CREATE INDEX idx_entitlements_payment_state
    ON user_entitlements (payment_state, status, valid_to_utc);

-- Disable legacy backend-issued internal trial rows.
-- Official trials must come from Google Play offerPhase = FREE_TRIAL.
UPDATE user_entitlements
SET status = 'EXPIRED',
    payment_state = 'EXPIRED',
    close_reason = 'LEGACY_INTERNAL_TRIAL_DISABLED',
    valid_to_utc = CASE
                       WHEN valid_to_utc > UTC_TIMESTAMP(6)
                           THEN UTC_TIMESTAMP(6)
                       ELSE valid_to_utc
        END,
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE source = 'INTERNAL'
  AND entitlement_type = 'TRIAL'
  AND status = 'ACTIVE';