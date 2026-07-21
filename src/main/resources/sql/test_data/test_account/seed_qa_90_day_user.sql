-- ============================================================================
-- CaloShape local QA fixture: single 120-day active user (no referral data)
-- ============================================================================
-- Purpose
--   Create one realistic, non-production account with more than 90 days of
--   activity, nutrition, water, weight, workout, food, login and audit data.
--
-- This fixture intentionally does NOT insert data into any referral table:
--   user_referral_codes, referral_claims, referral_case_snapshot,
--   referral_risk_signals, membership_reward_ledger,
--   entitlement_transfer_audit.
-- It also does NOT create an account-deletion request, image blob, active
-- login token, pending email, or Google Play entitlement.
--
-- Safe to run repeatedly
--   The script removes and recreates only the account identified by @qa_email.
--   It never truncates a table and never deletes another account.
--
-- IMPORTANT
--   1. Run only against a local/development database after Flyway V1/V2.
--   2. Keep the default fake email in source control. If you need OTP login,
--      change @qa_email only in a local, uncommitted copy of this script.
--   3. No schema change: this is test data only. No table or column is added.
--   4. This account has an expired historical DEV trial so it is not suitable
--      for Play Billing trial testing. Use a dedicated Play test account there.
-- ============================================================================

START TRANSACTION;

SET @qa_email = 'qa.90day.user@example.test';
SET @qa_timezone = 'Asia/Taipei';
-- Inclusive range ending today: 120 calendar dates in total.
SET @history_start = CURDATE() - INTERVAL 119 DAY;
SET @history_end = CURDATE();
SET @fixture_seed = 101;

-- --------------------------------------------------------------------------
-- Fixture cleanup: only rows belonging to this one QA account.
-- --------------------------------------------------------------------------
SET @qa_user_id = NULL;
SELECT id INTO @qa_user_id
FROM users
WHERE email = (CONVERT(@qa_email USING utf8mb4) COLLATE utf8mb4_unicode_ci)
LIMIT 1;

-- TABLE: food_log_overrides / food_log_tasks / food_log_requests / food_logs
-- Workbench Safe Updates only accepts a primary-key predicate at the outer
-- DELETE level. The derived tables keep this scoped to the fixture's logs.
DELETE FROM food_log_overrides
WHERE id IN (
    SELECT target_id
    FROM (
        SELECT o.id AS target_id
        FROM food_log_overrides o
        INNER JOIN food_logs f ON f.id = o.food_log_id
        WHERE f.user_id = @qa_user_id
    ) AS override_targets
) LIMIT 100000;

DELETE FROM food_log_tasks
WHERE id IN (
    SELECT target_id
    FROM (
        SELECT t.id AS target_id
        FROM food_log_tasks t
        INNER JOIN food_logs f ON f.id = t.food_log_id
        WHERE f.user_id = @qa_user_id
    ) AS task_targets
) LIMIT 100000;

DELETE r FROM food_log_requests r WHERE r.user_id = @qa_user_id;
DELETE FROM food_logs WHERE user_id = @qa_user_id;

-- TABLE: user_daily_activity / user_daily_nutrition_summary /
--        user_daily_workout_summary / user_water_daily
DELETE FROM user_daily_activity WHERE user_id = @qa_user_id;
DELETE FROM user_daily_nutrition_summary WHERE user_id = @qa_user_id;
DELETE FROM user_daily_workout_summary WHERE user_id = @qa_user_id;
DELETE FROM user_water_daily WHERE user_id = @qa_user_id;

-- TABLE: workout_session / weight_history / weight_timeseries
DELETE FROM workout_session WHERE user_id = @qa_user_id;
DELETE FROM weight_history WHERE user_id = @qa_user_id;
DELETE FROM weight_timeseries WHERE user_id = @qa_user_id;

-- TABLE: usage_counters / user_ai_quota_state / user_notifications
DELETE FROM usage_counters WHERE user_id = @qa_user_id;
DELETE FROM user_ai_quota_state WHERE user_id = @qa_user_id;
DELETE FROM user_notifications WHERE user_id = @qa_user_id;

-- TABLE: email_outbox / email_login_codes / auth_tokens
-- Use the indexed user_id so this script also works with MySQL Workbench
-- Safe Updates enabled. Fixture outbox rows always have this user_id.
DELETE FROM email_outbox WHERE user_id = @qa_user_id;
DELETE FROM email_login_codes WHERE email = @qa_email;
DELETE FROM auth_tokens WHERE user_id = @qa_user_id;

-- TABLE: fasting_plan / user_profiles / user_entitlements / users
DELETE FROM fasting_plan WHERE user_id = @qa_user_id;
DELETE FROM user_profiles WHERE user_id = @qa_user_id;
DELETE FROM user_entitlements WHERE user_id = @qa_user_id;
DELETE FROM users WHERE id = @qa_user_id;

-- --------------------------------------------------------------------------
-- TABLE: users
-- A verified EMAIL user created 120 days ago. Password hash is deliberately
-- NULL because local login should use OTP, not a seeded password.
-- --------------------------------------------------------------------------
INSERT INTO users (
    google_sub, email, password_hash, provider, status, email_verified,
    name, picture, created_at, updated_at, last_login_at
) VALUES (
    NULL, @qa_email, NULL, 'EMAIL', 'ACTIVE', 1,
    'QA 90-Day User', NULL,
    @history_start, NOW(), NOW() - INTERVAL 1 DAY
);
SET @qa_user_id = LAST_INSERT_ID();

-- --------------------------------------------------------------------------
-- TABLE: user_profiles
-- --------------------------------------------------------------------------
INSERT INTO user_profiles (
    user_id, gender, age, height_cm, weight_kg, weight_lbs,
    exercise_level, workouts_per_week, daily_workout_goal_kcal, goal,
    daily_step_goal, goal_weight_kg, goal_weight_lbs, unit_preference,
    kcal, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sodium_mg,
    water_ml, water_mode, bmi, bmi_class, plan_mode, calc_version,
    referral_source, locale, timezone
) VALUES (
    @qa_user_id, 'OTHER', 30, 170.00, 68.4, 150.8,
    'MODERATE', 3, 400, 'MAINTAIN',
    8000, 67.0, 147.7, 'KG',
    2100, 130, 240, 70, 30, 45, 2300,
    2200, 'AUTO', 23.7, 'NORMAL', 'AUTO', 'healthcalc_v1',
    NULL, 'zh-TW', @qa_timezone
);

-- Read the actual profile goals. Daily fixture values below are generated
-- relative to these values, not from fixed nutrition / activity ranges.
SELECT kcal, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sodium_mg,
       water_ml, daily_step_goal, daily_workout_goal_kcal
INTO @daily_kcal_goal, @protein_goal, @carbs_goal, @fat_goal, @fiber_goal,
     @sugar_goal, @sodium_goal, @water_goal, @step_goal, @workout_goal
FROM user_profiles
WHERE user_id = @qa_user_id;

-- --------------------------------------------------------------------------
-- TABLE: fasting_plan
-- --------------------------------------------------------------------------
INSERT INTO fasting_plan (user_id, plan_code, start_time, end_time, enabled, time_zone)
VALUES (@qa_user_id, '16:8', '20:00', '12:00', 1, @qa_timezone);

-- --------------------------------------------------------------------------
-- TABLE: user_entitlements
-- Historical expired DEV trial only; no Google Play purchase token is stored.
-- --------------------------------------------------------------------------
INSERT INTO user_entitlements (
    id, user_id, entitlement_type, status, valid_from_utc, valid_to_utc,
    source, product_id, subscription_state, payment_state, offer_phase,
    auto_renew_enabled, acknowledgement_state, close_reason
) VALUES (
    UUID(), @qa_user_id, 'TRIAL', 'EXPIRED',
    @history_start, @history_start + INTERVAL 3 DAY,
    'DEV', 'qa_history_trial', 'EXPIRED', 'EXPIRED', 'FREE_TRIAL',
    0, 'ACKNOWLEDGED', 'QA_HISTORY'
);

-- --------------------------------------------------------------------------
-- TABLE: user_daily_activity
-- More than 90 days of variable activity; deliberate no-record dates exist.
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
    @qa_user_id, d.local_date, @qa_timezone,
    TIMESTAMP(d.local_date, '00:00:00') - INTERVAL 8 HOUR,
    TIMESTAMP(d.local_date + INTERVAL 1 DAY, '00:00:00') - INTERVAL 8 HOUR,
    -- Varies around this user's step goal: some days are below, others above.
    GREATEST(0, @step_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':steps')), 7001) AS SIGNED) - 3500),
    -- Daily active calories: a variable fraction of this user's workout goal.
    GREATEST(0, FLOOR(@workout_goal * 0.20) + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':active-kcal')), 161) AS SIGNED) - 80),
    'IMPORT', 'com.caloshape.app.dev', 'QA 120-day fixture import',
    TIMESTAMP(d.local_date, '10:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':activity-time')), 600) MINUTE,
    TIMESTAMP(d.local_date, '10:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':activity-time')), 600) MINUTE
FROM dates d
-- Deterministic but non-periodic gaps: roughly 22% of days have no daily
-- records at all, simulating dates when the user recorded nothing.
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':daily-record')), 100) >= 22;

-- --------------------------------------------------------------------------
-- TABLE: user_daily_nutrition_summary
-- --------------------------------------------------------------------------
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
    @qa_user_id, d.local_date, @qa_timezone,
    -- Each macro varies above/below this account's own configured target.
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

-- --------------------------------------------------------------------------
-- TABLE: user_water_daily
-- --------------------------------------------------------------------------
INSERT INTO user_water_daily (user_id, local_date, cups, ml, fl_oz, updated_at)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @qa_user_id, d.local_date,
    ROUND(GREATEST(0, @water_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-ml')), 1401) AS SIGNED) - 700) / 237),
    GREATEST(0, @water_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-ml')), 1401) AS SIGNED) - 700),
    ROUND(GREATEST(0, @water_goal + CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-ml')), 1401) AS SIGNED) - 700) / 29.5735),
    TIMESTAMP(d.local_date, '12:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':water-time')), 600) MINUTE
FROM dates d
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':daily-record')), 100) >= 22;

-- --------------------------------------------------------------------------
-- TABLE: weight_history / weight_timeseries
-- Irregular weigh-ins with a gradual trend and realistic day-to-day variation.
-- --------------------------------------------------------------------------
INSERT INTO weight_history (user_id, log_date, weight_kg, weight_lbs, timezone, photo_url, created_at, updated_at)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @qa_user_id, d.local_date,
    ROUND(70.0 - DATEDIFF(d.local_date, @history_start) * 0.013
        + (CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-value')), 101) AS SIGNED) - 50) / 100, 1),
    ROUND((70.0 - DATEDIFF(d.local_date, @history_start) * 0.013
        + (CAST(MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-value')), 101) AS SIGNED) - 50) / 100) * 2.20462, 1),
    @qa_timezone, NULL,
    TIMESTAMP(d.local_date, '06:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-time')), 240) MINUTE,
    TIMESTAMP(d.local_date, '06:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-time')), 240) MINUTE
FROM dates d
WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':weight-record')), 100) < 16;

INSERT INTO weight_timeseries (user_id, log_date, weight_kg, weight_lbs, timezone, created_at)
SELECT user_id, log_date, weight_kg, weight_lbs, timezone, created_at
FROM weight_history
WHERE user_id = @qa_user_id;

-- --------------------------------------------------------------------------
-- TABLE: workout_session / user_daily_workout_summary
-- Variable sessions occur on several days each week, using an existing workout dictionary.
-- --------------------------------------------------------------------------
INSERT INTO workout_session (user_id, dictionary_id, minutes, kcal, started_at, local_date, timezone, created_at)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
)
SELECT
    @qa_user_id,
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
    WHERE user_id = @qa_user_id
    GROUP BY user_id, local_date
) w ON w.user_id = a.user_id AND w.local_date = a.local_date
WHERE a.user_id = @qa_user_id;

-- --------------------------------------------------------------------------
-- TABLE: food_logs / food_log_tasks / food_log_requests / food_log_overrides
-- Completed fixture records only. No images or external AI request is queued.
-- --------------------------------------------------------------------------
SET @food_effective = JSON_OBJECT(
    'foodName', 'QA chicken rice bowl',
    'quantity', JSON_OBJECT('value', 1, 'unit', 'SERVING'),
    'nutrients', JSON_OBJECT('kcal', 520, 'protein', 32, 'fat', 16, 'carbs', 62, 'fiber', 6, 'sugar', 8, 'sodium', 730),
    'healthScore', 7.5,
    'confidence', 0.90
);

INSERT INTO food_logs (
    id, user_id, status, method, provider, captured_at_utc, captured_tz,
    captured_local_date, server_received_at_utc, time_source, time_suspect,
    effective, base_effective, portion_multiplier, created_at_utc, updated_at_utc, saved_at_utc
)
WITH RECURSIVE dates AS (
    SELECT @history_start AS local_date
    UNION ALL
    SELECT local_date + INTERVAL 1 DAY FROM dates WHERE local_date < @history_end
),
picked_food_dates AS (
    SELECT local_date
    FROM dates
    WHERE MOD(CRC32(CONCAT(@fixture_seed, ':', local_date, ':daily-record')), 100) >= 22
    ORDER BY CRC32(CONCAT(@fixture_seed, ':', local_date, ':food-record'))
    LIMIT 5
)
SELECT
    UUID(), @qa_user_id, 'SAVED', 'BARCODE', 'STUB',
    TIMESTAMP(d.local_date, '07:00:00') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':food-time')), 840) MINUTE, @qa_timezone, d.local_date,
    TIMESTAMP(d.local_date, '07:00:10') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':food-time')), 840) MINUTE, 'DEVICE_CLOCK', 0,
    @food_effective, @food_effective, 1,
    TIMESTAMP(d.local_date, '07:00:10') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':food-time')), 840) MINUTE,
    TIMESTAMP(d.local_date, '07:00:10') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':food-time')), 840) MINUTE,
    TIMESTAMP(d.local_date, '07:00:10') + INTERVAL MOD(CRC32(CONCAT(@fixture_seed, ':', d.local_date, ':food-time')), 840) MINUTE
FROM picked_food_dates d;

INSERT INTO food_log_tasks (id, food_log_id, task_status, attempts, poll_after_sec, created_at_utc, updated_at_utc)
SELECT UUID(), id, 'SUCCEEDED', 1, 2, created_at_utc, updated_at_utc
FROM food_logs WHERE user_id = @qa_user_id;

INSERT INTO food_log_requests (user_id, request_id, food_log_id, status, created_at_utc, updated_at_utc)
SELECT user_id, CONCAT('qa-90day-', REPLACE(id, '-', '')), id, 'ATTACHED', created_at_utc, updated_at_utc
FROM food_logs WHERE user_id = @qa_user_id;

INSERT INTO food_log_overrides (id, food_log_id, field_key, old_value_json, new_value_json, editor_type, reason, edited_at_utc)
SELECT UUID(), id, 'QUANTITY', JSON_OBJECT('value', 1), JSON_OBJECT('value', 1), 'USER', 'QA history fixture', updated_at_utc
FROM food_logs WHERE user_id = @qa_user_id ORDER BY captured_at_utc LIMIT 1;

-- --------------------------------------------------------------------------
-- TABLE: usage_counters / user_ai_quota_state
-- Historical quota information only; no live limit or cooldown is triggered.
-- --------------------------------------------------------------------------
INSERT INTO usage_counters (user_id, local_date, used_count, updated_at_utc)
VALUES (@qa_user_id, CURDATE(), 2, NOW(6));

INSERT INTO user_ai_quota_state (
    user_id, daily_key, daily_count, monthly_key, monthly_count,
    cooldown_strikes, next_allowed_at_utc, force_low_until_utc, cooldown_reason
) VALUES (
    @qa_user_id, CONCAT(CURDATE(), '@', @qa_timezone), 2,
    CONCAT(DATE_FORMAT(CURDATE(), '%Y-%m'), '@', @qa_timezone), 18,
    0, NULL, NULL, NULL
);

-- --------------------------------------------------------------------------
-- TABLE: user_notifications / email_outbox / email_login_codes / auth_tokens
-- All are historical/safe: no pending message and no usable authentication token.
-- --------------------------------------------------------------------------
INSERT INTO user_notifications (
    user_id, type, title, message, deep_link, source_type, source_ref_id, is_read, created_at_utc
) VALUES (
    @qa_user_id, 'FASTING_REMINDER', 'QA fasting reminder',
    'Historical QA notification.', '/fasting', 'QA_FIXTURE', 900001, 1,
    NOW(6) - INTERVAL 10 DAY
);

INSERT INTO email_outbox (
    user_id, to_email, template_type, template_payload_json, dedupe_key,
    retry_count, status, created_at_utc, sent_at_utc
) VALUES (
    @qa_user_id, @qa_email, 'LOGIN_CODE', JSON_OBJECT('fixture', true),
    CONCAT('qa-90day-sent-', @qa_user_id), 0, 'SENT',
    NOW(6) - INTERVAL 60 DAY, NOW(6) - INTERVAL 60 DAY
);

INSERT INTO email_login_codes (
    email, code_hash, purpose, expires_at, consumed_at, created_at, attempt_cnt, client_ip, user_agent
) VALUES (
    @qa_email, SHA2('QA-EXPIRED-OTP-NOT-USABLE', 256), 'LOGIN',
    NOW() - INTERVAL 60 DAY, NOW() - INTERVAL 60 DAY,
    NOW() - INTERVAL 60 DAY, 1, '127.0.0.1', 'qa-fixture'
);

INSERT INTO auth_tokens (
    token, user_id, type, expires_at, created_at, revoked, replaced_by, device_id, client_ip, user_agent
) VALUES (
    -- Include the fixture account identity: auth_tokens.token is globally unique,
    -- so separate QA accounts must never share the same deterministic hash.
    SHA2(CONCAT('QA-EXPIRED-REFRESH-NOT-USABLE:', @qa_email), 256), @qa_user_id, 'REFRESH',
    NOW() - INTERVAL 60 DAY, NOW() - INTERVAL 90 DAY, 1, NULL,
    'qa-fixture-device', '127.0.0.1', 'qa-fixture'
);

COMMIT;

-- Verification query (read-only; run after COMMIT if desired):
-- SELECT u.id, u.email, u.created_at,
--        (SELECT COUNT(*) FROM user_daily_activity a WHERE a.user_id = u.id) AS activity_days,
--        (SELECT COUNT(*) FROM food_logs f WHERE f.user_id = u.id) AS food_logs
-- FROM users u WHERE u.email = @qa_email;
