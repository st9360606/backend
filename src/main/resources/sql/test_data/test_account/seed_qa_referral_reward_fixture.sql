-- ============================================================================
-- CaloShape local QA fixture: referral, reward, risk and notification data
-- ============================================================================
-- Purpose
--   Create two fake local QA users and one completed referral case:
--   1. an inviter with a referral code;
--   2. an invitee who completed the referral journey;
--   3. a granted internal reward, a low-risk signal, and user notifications.
--
-- This fixture is for referral UI/API testing only. It never stores a raw
-- Google Play purchase token and never creates a pending worker task.
--
-- IMPORTANT
--   * Run only on a local/development database after Flyway V1/V2.
--   * Keep the default fake emails in source control. Change them only in an
--     uncommitted local copy if OTP login is required.
--   * No schema change: this file creates test rows only; no table/column is
--     added or modified.
-- ============================================================================

START TRANSACTION;

SET @inviter_email = 'qa.referral.inviter@example.test';
SET @invitee_email = 'qa.referral.invitee@example.test';
SET @promo_code = 'QA90REF2026';
SET @qa_timezone = 'Asia/Taipei';
-- Inclusive range ending today: 120 calendar dates in total.
SET @history_start = CURDATE() - INTERVAL 119 DAY;
SET @history_end = CURDATE();
SET @fixture_seed = 202;

-- --------------------------------------------------------------------------
-- Resolve prior fixture users. Cleanup is limited to the two fake emails and
-- this fixed QA promo code.
-- --------------------------------------------------------------------------
SET @inviter_user_id = NULL;
SET @invitee_user_id = NULL;
SELECT id INTO @inviter_user_id
FROM users
WHERE email = (CONVERT(@inviter_email USING utf8mb4) COLLATE utf8mb4_unicode_ci)
LIMIT 1;

SELECT id INTO @invitee_user_id
FROM users
WHERE email = (CONVERT(@invitee_email USING utf8mb4) COLLATE utf8mb4_unicode_ci)
LIMIT 1;

SET @existing_claim_id = NULL;
SELECT id INTO @existing_claim_id
FROM referral_claims
WHERE invitee_user_id = @invitee_user_id
LIMIT 1;

-- TABLE: referral_risk_signals / membership_reward_ledger / user_notifications
-- Delete dependent rows before deleting the claim. Every DELETE uses an indexed
-- key column so it can run with MySQL Workbench Safe Updates enabled.
DELETE FROM referral_risk_signals WHERE claim_id = @existing_claim_id;

DELETE FROM membership_reward_ledger WHERE user_id = @inviter_user_id;
DELETE FROM membership_reward_ledger WHERE user_id = @invitee_user_id;

DELETE FROM user_notifications
WHERE user_id IN (@inviter_user_id, @invitee_user_id)
  AND source_type IN ('REFERRAL_CLAIM', 'MEMBERSHIP_REWARD_LEDGER');

-- TABLE: referral_case_snapshot / referral_claims / user_referral_codes
DELETE FROM referral_case_snapshot
WHERE inviter_user_id = @inviter_user_id;

DELETE FROM referral_claims WHERE inviter_user_id = @inviter_user_id;
DELETE FROM referral_claims WHERE invitee_user_id = @invitee_user_id;

DELETE FROM user_referral_codes WHERE user_id = @inviter_user_id;
DELETE FROM user_referral_codes WHERE user_id = @invitee_user_id;

-- TABLE: user_entitlements / user_profiles / users
DELETE FROM user_daily_activity WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM user_daily_nutrition_summary WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM user_daily_workout_summary WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM user_water_daily WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM workout_session WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM weight_history WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM weight_timeseries WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM user_entitlements WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM user_profiles WHERE user_id IN (@inviter_user_id, @invitee_user_id);
DELETE FROM users WHERE id IN (@inviter_user_id, @invitee_user_id);

-- --------------------------------------------------------------------------
-- TABLE: users
-- Two verified EMAIL accounts. They are fake identities used only locally.
-- --------------------------------------------------------------------------
INSERT INTO users (
    email, provider, status, email_verified, name, created_at, updated_at, last_login_at
) VALUES (
    @inviter_email, 'EMAIL', 'ACTIVE', 1, 'QA Referral Inviter',
    NOW() - INTERVAL 120 DAY, NOW(), NOW() - INTERVAL 1 DAY
);
SET @inviter_user_id = LAST_INSERT_ID();

INSERT INTO users (
    email, provider, status, email_verified, name, created_at, updated_at, last_login_at
) VALUES (
    @invitee_email, 'EMAIL', 'ACTIVE', 1, 'QA Referral Invitee',
    NOW() - INTERVAL 30 DAY, NOW(), NOW() - INTERVAL 2 DAY
);
SET @invitee_user_id = LAST_INSERT_ID();

-- --------------------------------------------------------------------------
-- TABLE: user_profiles
-- --------------------------------------------------------------------------
INSERT INTO user_profiles (
    user_id, gender, age, height_cm, weight_kg, weight_lbs,
    exercise_level, workouts_per_week, daily_workout_goal_kcal, goal,
    daily_step_goal, goal_weight_kg, goal_weight_lbs, unit_preference,
    kcal, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sodium_mg,
    water_ml, water_mode, bmi, bmi_class, plan_mode, calc_version,
    locale, timezone
) VALUES
(
    @inviter_user_id, 'OTHER', 31, 172.0, 70.0, 154.3,
    'MODERATE', 3, 400, 'MAINTAIN', 8000, 69.0, 152.1, 'KG',
    2100, 130, 240, 70, 30, 45, 2300,
    2200, 'AUTO', 23.7, 'NORMAL', 'AUTO', 'healthcalc_v1', 'zh-TW', @qa_timezone
),
(
    @invitee_user_id, 'OTHER', 28, 165.0, 58.0, 127.9,
    'LIGHT', 2, 300, 'LOSE_WEIGHT', 7000, 55.0, 121.3, 'KG',
    1750, 110, 190, 55, 28, 40, 2200,
    2000, 'AUTO', 21.3, 'NORMAL', 'AUTO', 'healthcalc_v1', 'zh-TW', @qa_timezone
);

-- Read the main inviter's configured goals. Its daily test data is generated
-- around these goals instead of relying on fixed global ranges.
SELECT kcal, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sodium_mg,
       water_ml, daily_step_goal, daily_workout_goal_kcal
INTO @daily_kcal_goal, @protein_goal, @carbs_goal, @fat_goal, @fiber_goal,
     @sugar_goal, @sodium_goal, @water_goal, @step_goal, @workout_goal
FROM user_profiles
WHERE user_id = @inviter_user_id;

-- --------------------------------------------------------------------------
-- TABLE: user_daily_activity / user_daily_nutrition_summary /
--        user_water_daily / weight_history / weight_timeseries /
--        workout_session / user_daily_workout_summary
-- The inviter is the single main QA account. It has more than 90 days of
-- variable history, with deliberate no-record dates, weekly weights and workouts.
-- The invitee is deliberately minimal because it exists only to form the
-- referral relationship.
-- --------------------------------------------------------------------------
INSERT INTO user_daily_activity (
    user_id, local_date, timezone, day_start_utc, day_end_utc,
    steps, active_kcal, ingest_source, data_origin_package, data_origin_name,
    ingested_at, updated_at
)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @inviter_user_id, d.local_date, @qa_timezone,
    TIMESTAMP(d.local_date, '00:00:00') - INTERVAL 8 HOUR,
    TIMESTAMP(d.local_date + INTERVAL 1 DAY, '00:00:00') - INTERVAL 8 HOUR,
    GREATEST(0, @step_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':steps')), 7001) AS SIGNED) - 3500),
    GREATEST(0, FLOOR(@workout_goal * 0.20) + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':active-kcal')), 161) AS SIGNED) - 80),
    'IMPORT', 'com.caloshape.app.dev', 'QA 120-day referral fixture import',
    TIMESTAMP(d.local_date, '10:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':activity-time')), 600) MINUTE,
    TIMESTAMP(d.local_date, '10:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':activity-time')), 600) MINUTE
FROM dates d
-- Deterministic but non-periodic gaps: roughly 22% of dates have no daily
-- records at all, simulating dates when the user recorded nothing.
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':daily-record')), 100) >= 22;

INSERT INTO user_daily_nutrition_summary (
    user_id, local_date, timezone, total_kcal, total_protein_g,
    total_carbs_g, total_fats_g, total_fiber_g, total_sugar_g,
    total_sodium_mg, avg_health_score, meal_count,
    last_recomputed_at_utc, created_at_utc, updated_at_utc
)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @inviter_user_id, d.local_date, @qa_timezone,
    GREATEST(900, @daily_kcal_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':kcal')), 1201) AS SIGNED) - 600),
    GREATEST(0, @protein_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':protein')), 81) AS SIGNED) - 40),
    GREATEST(0, @carbs_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':carbs')), 201) AS SIGNED) - 100),
    GREATEST(0, @fat_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':fat')), 51) AS SIGNED) - 25),
    GREATEST(0, @fiber_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':fiber')), 31) AS SIGNED) - 15),
    GREATEST(0, @sugar_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':sugar')), 61) AS SIGNED) - 30),
    GREATEST(0, @sodium_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':sodium')), 1801) AS SIGNED) - 900),
    CAST(ROUND(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':health-score')), 101) / 10, 1) AS DECIMAL(3,1)),
    1 + MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':meal-count')), 5),
    TIMESTAMP(d.local_date, '18:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':nutrition-time')), 240) MINUTE,
    TIMESTAMP(d.local_date, '18:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':nutrition-time')), 240) MINUTE,
    TIMESTAMP(d.local_date, '18:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':nutrition-time')), 240) MINUTE
FROM dates d
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':daily-record')), 100) >= 22;

INSERT INTO user_water_daily (user_id, local_date, cups, ml, fl_oz, updated_at)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @inviter_user_id, d.local_date,
    ROUND(GREATEST(0, @water_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-ml')), 1401) AS SIGNED) - 700) / 237),
    GREATEST(0, @water_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-ml')), 1401) AS SIGNED) - 700),
    ROUND(GREATEST(0, @water_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-ml')), 1401) AS SIGNED) - 700) / 29.5735),
    TIMESTAMP(d.local_date, '12:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-time')), 600) MINUTE
FROM dates d
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':daily-record')), 100) >= 22;

INSERT INTO weight_history (user_id, log_date, weight_kg, weight_lbs, timezone, photo_url, created_at, updated_at)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @inviter_user_id, d.local_date,
    ROUND(72.0 - DATEDIFF(d.local_date, @history_start) * 0.015
        + (CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-value')), 101) AS SIGNED) - 50) / 100, 1),
    ROUND((72.0 - DATEDIFF(d.local_date, @history_start) * 0.015
        + (CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-value')), 101) AS SIGNED) - 50) / 100) * 2.20462, 1),
    @qa_timezone, NULL,
    TIMESTAMP(d.local_date, '06:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-time')), 240) MINUTE,
    TIMESTAMP(d.local_date, '06:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-time')), 240) MINUTE
FROM dates d
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-record')), 100) < 16;

INSERT INTO weight_timeseries (user_id, log_date, weight_kg, weight_lbs, timezone, created_at)
SELECT user_id, log_date, weight_kg, weight_lbs, timezone, created_at
FROM weight_history WHERE user_id = @inviter_user_id;

INSERT INTO workout_session (user_id, dictionary_id, minutes, kcal, started_at, local_date, timezone, created_at)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @inviter_user_id,
    wd.id,
    15 + MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':workout-minutes')), 61),
    GREATEST(30,
        GREATEST(ROUND(@workout_goal * 0.70), @workout_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':workout-kcal')), 701) AS SIGNED) - 350)
        - GREATEST(0, FLOOR(@workout_goal * 0.20) + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':active-kcal')), 161) AS SIGNED) - 80)
    ),
    -- Workout time varies between local 06:00 and 18:59 (stored as UTC).
    TIMESTAMP(d.local_date, '06:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':workout-time')), 780) MINUTE - INTERVAL 8 HOUR,
    d.local_date, @qa_timezone,
    TIMESTAMP(d.local_date, '06:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':workout-time')), 780) MINUTE - INTERVAL 8 HOUR
FROM dates d
CROSS JOIN (SELECT id FROM workout_dictionary ORDER BY id LIMIT 1) wd
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':daily-record')), 100) >= 22
  AND MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':workout')), 100) < 45;

INSERT INTO user_daily_workout_summary (
    user_id, local_date, timezone, workout_kcal, activity_kcal, total_burned_kcal,
    workout_session_count, last_recomputed_at_utc, created_at_utc, updated_at_utc
)
SELECT
    a.user_id, a.local_date, a.timezone,
    COALESCE(w.workout_kcal, 0), a.active_kcal, COALESCE(w.workout_kcal, 0) + a.active_kcal,
    COALESCE(w.workout_session_count, 0),
    TIMESTAMP(a.local_date, '18:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', a.local_date, ':summary-time')), 240) MINUTE,
    TIMESTAMP(a.local_date, '18:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', a.local_date, ':summary-time')), 240) MINUTE,
    TIMESTAMP(a.local_date, '18:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', a.local_date, ':summary-time')), 240) MINUTE
FROM user_daily_activity a
LEFT JOIN (
    SELECT user_id, local_date, SUM(kcal) AS workout_kcal, COUNT(*) AS workout_session_count
    FROM workout_session
    WHERE user_id = @inviter_user_id
    GROUP BY user_id, local_date
) w ON w.user_id = a.user_id AND w.local_date = a.local_date
WHERE a.user_id = @inviter_user_id;

-- --------------------------------------------------------------------------
-- TABLE: user_entitlements
-- The inviter has an INTERNAL active entitlement. No Google Play token or
-- external billing state is seeded, so no worker can call Google Play.
-- --------------------------------------------------------------------------
SET @old_premium_until = NOW(6) + INTERVAL 14 DAY;
SET @new_premium_until = @old_premium_until + INTERVAL 7 DAY;

INSERT INTO user_entitlements (
    id, user_id, entitlement_type, status, valid_from_utc, valid_to_utc,
    source, product_id, subscription_state, payment_state, offer_phase,
    auto_renew_enabled, acknowledgement_state
) VALUES (
    UUID(), @inviter_user_id, 'MONTHLY', 'ACTIVE', NOW(6) - INTERVAL 16 DAY,
    @new_premium_until, 'INTERNAL', 'qa_referral_membership', 'ACTIVE', 'OK',
    'BASE', 0, 'ACKNOWLEDGED'
);

-- --------------------------------------------------------------------------
-- TABLE: user_referral_codes
-- A stable fixed promo code makes local test scripts and UI checks repeatable.
-- --------------------------------------------------------------------------
INSERT INTO user_referral_codes (user_id, promo_code, is_active, created_at_utc, updated_at_utc)
VALUES (@inviter_user_id, @promo_code, 1, NOW(6) - INTERVAL 100 DAY, NOW(6));

-- --------------------------------------------------------------------------
-- TABLE: referral_claims
-- Completed, rewarded case. Hashes are clearly fake SHA-256-sized test values;
-- they are not live device, IP, payment, or purchase-token information.
-- --------------------------------------------------------------------------
INSERT INTO referral_claims (
    inviter_user_id, invitee_user_id, promo_code, status, reject_reason,
    subscribed_at_utc, qualified_at_utc, verification_deadline_utc,
    cooldown_until_utc, rewarded_at_utc, refund_detected_at_utc,
    auto_renew_status, purchase_token_hash, risk_score, risk_decision,
    created_at_utc, updated_at_utc
) VALUES (
    @inviter_user_id, @invitee_user_id, @promo_code, 'REWARDED', 'NONE',
    NOW(6) - INTERVAL 21 DAY, NOW(6) - INTERVAL 14 DAY,
    NOW(6) - INTERVAL 7 DAY, NOW(6) - INTERVAL 7 DAY,
    NOW(6) - INTERVAL 6 DAY, NULL,
    'ON', SHA2('QA-REFERRAL-PURCHASE-NOT-REAL', 256), 7, 'ALLOW',
    NOW(6) - INTERVAL 30 DAY, NOW(6) - INTERVAL 6 DAY
);
SET @claim_id = LAST_INSERT_ID();

-- --------------------------------------------------------------------------
-- TABLE: referral_risk_signals
-- One approved low-risk signal to exercise risk/audit UI without modelling
-- actual fraud or retaining real device/IP/payment identifiers.
-- --------------------------------------------------------------------------
INSERT INTO referral_risk_signals (
    claim_id, device_hash, ip_hash, payment_fingerprint_hash,
    risk_score, risk_flags_json, decision, created_at_utc
) VALUES (
    @claim_id,
    SHA2('QA-DEVICE-NOT-REAL', 256), SHA2('QA-IP-NOT-REAL', 256), SHA2('QA-PAYMENT-NOT-REAL', 256),
    7, JSON_ARRAY('QA_FIXTURE_LOW_RISK'), 'ALLOW', NOW(6) - INTERVAL 14 DAY
);

-- --------------------------------------------------------------------------
-- TABLE: membership_reward_ledger
-- A completed internal grant of seven days. Status is GRANTED, so the worker
-- has nothing to retry and no Google Play defer request is represented.
-- --------------------------------------------------------------------------
INSERT INTO membership_reward_ledger (
    user_id, source_type, source_ref_id, attempt_no, trace_id,
    grant_status, reward_channel, google_purchase_token_hash,
    google_defer_status, google_defer_request_json, google_defer_response_json,
    google_defer_http_status, error_code, error_message, days_added,
    old_premium_until, new_premium_until, next_retry_at_utc, granted_at_utc
) VALUES (
    @inviter_user_id, 'REFERRAL_CLAIM', @claim_id, 1, 'qa-referral-grant-001',
    'GRANTED', 'INTERNAL', NULL,
    NULL, NULL, NULL, NULL, NULL, NULL, 7,
    @old_premium_until, @new_premium_until, NULL, NOW(6) - INTERVAL 6 DAY
);
SET @reward_ledger_id = LAST_INSERT_ID();

-- --------------------------------------------------------------------------
-- TABLE: referral_case_snapshot
-- --------------------------------------------------------------------------
INSERT INTO referral_case_snapshot (
    inviter_user_id, total_invited, success_count, rejected_count,
    pending_verification_count, total_rewarded_days, current_premium_until,
    updated_at_utc
) VALUES (
    @inviter_user_id, 1, 1, 0, 0, 7, @new_premium_until, NOW(6)
);

-- --------------------------------------------------------------------------
-- TABLE: user_notifications
-- One referral status notification for the invitee and one reward notification
-- for the inviter. These are already read historical messages.
-- --------------------------------------------------------------------------
INSERT INTO user_notifications (
    user_id, type, title, message, deep_link, source_type, source_ref_id,
    is_read, created_at_utc
) VALUES
(
    @invitee_user_id, 'REFERRAL_REWARDED', 'Referral completed',
    'Your referral qualification was completed for QA testing.',
    '/membership/referral', 'REFERRAL_CLAIM', @claim_id, 1, NOW(6) - INTERVAL 6 DAY
),
(
    @inviter_user_id, 'REFERRAL_REWARD_GRANTED', 'Reward granted',
    'Seven QA membership reward days were granted.',
    '/membership/referral', 'MEMBERSHIP_REWARD_LEDGER', @reward_ledger_id, 1, NOW(6) - INTERVAL 6 DAY
);

COMMIT;

-- Verification query (read-only; run after COMMIT if desired):
-- SELECT c.id, c.promo_code, c.status, c.risk_score, c.risk_decision,
--        l.grant_status, l.days_added, s.total_rewarded_days
-- FROM referral_claims c
-- JOIN membership_reward_ledger l ON l.source_type = 'REFERRAL_CLAIM' AND l.source_ref_id = c.id
-- JOIN referral_case_snapshot s ON s.inviter_user_id = c.inviter_user_id
-- WHERE c.id = @claim_id;
