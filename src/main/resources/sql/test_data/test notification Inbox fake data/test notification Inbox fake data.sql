-- ============================================================
-- CaloShape Notification Inbox test data
-- Target user_id = 42
-- Table: user_notifications
-- Columns:
-- id, user_id, type, title, message, deep_link, source_type,
-- source_ref_id, is_read, created_at_utc
-- ============================================================

SET @user_id := 42;

START TRANSACTION;

INSERT INTO user_notifications (
    user_id,
    type,
    title,
    message,
    deep_link,
    source_type,
    source_ref_id,
    is_read,
    created_at_utc
)
VALUES
    (
        @user_id,
        'GRANTED',
        '🎉 30 free Premium days unlocked',
        'Your CaloShape Premium has been extended by 30 days.\nNew expiry: 2026-06-30',
        'caloshape://settings/inbox/referral/910001',
        'REFERRAL_CLAIM',
        910001,
        FALSE,
        UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE
    ),
    (
        @user_id,
        'REMINDER',
        'Meal reminder',
        'Don''t forget to log your lunch today.',
        'caloshape://home',
        'SYSTEM',
        910002,
        FALSE,
        UTC_TIMESTAMP(6) - INTERVAL 35 MINUTE
    ),
    (
        @user_id,
        'PAYMENT_ISSUE',
        'Payment needs attention',
        'We could not renew your CaloShape subscription. Please check your payment method.',
        'caloshape://settings/subscription',
        'SUBSCRIPTION',
        910003,
        FALSE,
        UTC_TIMESTAMP(6) - INTERVAL 2 HOUR
    ),
    (
        @user_id,
        'REFERRAL_REJECTED',
        'Referral reward not granted',
        'This referral did not qualify.\nReason: The invited account did not complete a valid paid subscription.',
        'caloshape://settings/inbox/referral/910004',
        'REFERRAL_CLAIM',
        910004,
        TRUE,
        UTC_TIMESTAMP(6) - INTERVAL 1 DAY
    ),
    (
        @user_id,
        'SYSTEM',
        'Welcome to CaloShape',
        'Your Inbox is ready. Important account and reward updates will appear here.',
        'caloshape://settings/inbox',
        'SYSTEM',
        910005,
        TRUE,
        UTC_TIMESTAMP(6) - INTERVAL 3 DAY
    );

COMMIT;
