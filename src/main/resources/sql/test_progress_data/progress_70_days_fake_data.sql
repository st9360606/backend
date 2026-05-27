-- ============================================================
-- BiteCal / Calai ProgressScreen random 50 fake data in last 70 days
-- Target: MySQL 8.x

-- Purpose:
--   - Create 50 random DEV test data rows within the latest 70 days.
--   - Main chart values are different every day.
--   - Useful for checking ProgressScreen week switching:
--     This Week / Last Week / 2 wks. ago / 3 wks. ago.
--

-- Tables:
--   - user_daily_nutrition_summary
--   - user_water_daily
--   - user_daily_activity
--   - workout_session
--   - user_daily_workout_summary

-- Fixed version:
--   1. Uses 50 random dates within the last 70 days, not continuous 50 days.
--   2. Forces 2 random gaps in every 7-day bucket, so the 50 selected dates
--      are guaranteed to be non-continuous.
--   3. Uses the requested nutrition ranges.
--   4. Inserts avg_health_score because current user_daily_nutrition_summary
--      table already contains avg_health_score.
--   5. Temporarily disables SQL_SAFE_UPDATES and restores it after COMMIT.
--   6. Verification queries avoid MySQL Error 1137:
--      "Can't reopen table: tmp_progress_50_days".
--
-- DEV only. Do NOT run this against production data.

/**
最近 70 天隨機 50 筆
avg_health_score 0-10分
卡路里 的範圍2200~2900
蛋白質的範圍150~220
碳水的範圍 200~400
脂肪的範圍45~90
糖的範圍40~90
纖維的範圍20~60
鈉的範圍1800~3000
總喝水量 2000~3500
總運動消耗 100~500
總跑步數 4000~10000
*/
-- ============================================================

SET @user_id := 1;
SET @timezone := 'Asia/Taipei';

-- Use Taiwan local date without depending on MySQL timezone tables.
SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR);
SET @range_start := DATE_SUB(@today, INTERVAL 69 DAY);

-- ============================================================
-- Build 70-day candidate date range.
-- ============================================================

DROP TEMPORARY TABLE IF EXISTS tmp_progress_all_days;
CREATE TEMPORARY TABLE tmp_progress_all_days (
                                                 day_offset INT NOT NULL PRIMARY KEY,
                                                 local_date DATE NOT NULL UNIQUE
);

INSERT INTO tmp_progress_all_days (day_offset, local_date)
WITH RECURSIVE seq(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 69
)
SELECT
    n AS day_offset,
    DATE_ADD(@range_start, INTERVAL n DAY) AS local_date
FROM seq;

-- ============================================================
-- Pick 50 deterministic-random dates from the last 70 days.
--
-- Implementation:
--   - Split 70 days into 10 buckets.
--   - Each bucket has 7 days.
--   - Randomly skip 2 days per bucket.
--   - Keep 5 days per bucket.
--   - 10 buckets * 5 kept days = 50 selected dates.
--
-- Result:
--   - Same @user_id + same date range produces stable test dates.
--   - Dates are guaranteed to be non-continuous because each 6-day bucket
--     has at least 1 missing day.
-- ============================================================

DROP TEMPORARY TABLE IF EXISTS tmp_progress_50_days;
CREATE TEMPORARY TABLE tmp_progress_50_days (
                                                rn INT NOT NULL PRIMARY KEY,
                                                day_offset INT NOT NULL,
                                                local_date DATE NOT NULL UNIQUE
);

INSERT INTO tmp_progress_50_days (rn, day_offset, local_date)
WITH ranked_days AS (
    SELECT
        day_offset,
        local_date,
        FLOOR(day_offset / 7) AS bucket_no,
        ROW_NUMBER() OVER (
            PARTITION BY FLOOR(day_offset / 7)
            ORDER BY CRC32(CONCAT(@user_id, ':', local_date, ':bitecal-progress-random-50-v1'))
            ) AS bucket_rank
    FROM tmp_progress_all_days
),
     picked AS (
         SELECT
             day_offset,
             local_date
         FROM ranked_days
         WHERE bucket_rank > 2
     )
SELECT
    ROW_NUMBER() OVER (ORDER BY local_date) AS rn,
    day_offset,
    local_date
FROM picked
ORDER BY local_date;

-- ============================================================
-- Data write section
-- ============================================================

SET @old_sql_safe_updates := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

START TRANSACTION;

-- ------------------------------------------------------------
-- Clean only the 70-day test range for this user.
-- WARNING: DEV database only.
-- ------------------------------------------------------------
DELETE FROM user_daily_nutrition_summary
WHERE user_id = @user_id
  AND local_date BETWEEN @range_start AND @today;

DELETE FROM user_water_daily
WHERE user_id = @user_id
  AND local_date BETWEEN @range_start AND @today;

DELETE FROM user_daily_activity
WHERE user_id = @user_id
  AND local_date BETWEEN @range_start AND @today;

DELETE FROM user_daily_workout_summary
WHERE user_id = @user_id
  AND local_date BETWEEN @range_start AND @today;

DELETE FROM workout_session
WHERE user_id = @user_id
  AND local_date BETWEEN @range_start AND @today;

-- ------------------------------------------------------------
-- Ensure a workout dictionary row exists.
-- ------------------------------------------------------------
INSERT INTO workout_dictionary (
    canonical_key,
    display_name_en,
    met_value,
    icon_key,
    created_at
)
VALUES (
           'walking',
           'Walking',
           3.5,
           'walk',
           UTC_TIMESTAMP()
       )
ON DUPLICATE KEY UPDATE
                     display_name_en = VALUES(display_name_en),
                     met_value = VALUES(met_value),
                     icon_key = VALUES(icon_key);

SET @walking_dictionary_id := (
    SELECT id
    FROM workout_dictionary
    WHERE canonical_key = 'walking'
    LIMIT 1
);

-- ------------------------------------------------------------
-- 1) Nutrition fake data
--
-- Requested ranges:
--   calories         : 2200 ~ 2900
--   protein          : 150  ~ 220
--   carbs            : 200  ~ 400
--   fats             : 45   ~ 90
--   sugar            : 40   ~ 90
--   fiber            : 20   ~ 60
--   sodium           : 1800 ~ 3000
--   avg_health_score : 0    ~ 10
-- ------------------------------------------------------------
INSERT INTO user_daily_nutrition_summary (
    user_id,
    local_date,
    timezone,
    total_kcal,
    total_protein_g,
    total_carbs_g,
    total_fats_g,
    total_fiber_g,
    total_sugar_g,
    total_sodium_mg,
    avg_health_score,
    meal_count,
    last_recomputed_at_utc,
    created_at_utc,
    updated_at_utc
)
SELECT
    @user_id,
    d.local_date,
    @timezone,

    -- 2200 ~ 2900
    2200 + MOD(d.rn * 37 + d.day_offset * 11, 701) AS total_kcal,

    -- 150 ~ 220
    150 + MOD(d.rn * 19 + d.day_offset * 7, 71) AS total_protein_g,

    -- 200 ~ 400
    200 + MOD(d.rn * 23 + d.day_offset * 13, 201) AS total_carbs_g,

    -- 45 ~ 90
    45 + MOD(d.rn * 7 + d.day_offset * 5, 46) AS total_fats_g,

    -- 20 ~ 60
    20 + MOD(d.rn * 11 + d.day_offset * 3, 41) AS total_fiber_g,

    -- 40 ~ 90
    40 + MOD(d.rn * 13 + d.day_offset * 5, 51) AS total_sugar_g,

    -- 1800 ~ 3000
    1800 + MOD(d.rn * 97 + d.day_offset * 29, 1201) AS total_sodium_mg,

    -- 0.0 ~ 10.0
    CAST(ROUND(MOD(d.rn * 17 + d.day_offset * 9, 101) / 10, 1) AS DECIMAL(3,1)) AS avg_health_score,

    -- realistic meal count: 2 ~ 6
    2 + MOD(d.rn * 3 + d.day_offset, 5) AS meal_count,

    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
FROM tmp_progress_50_days d
ON DUPLICATE KEY UPDATE
                     timezone = VALUES(timezone),
                     total_kcal = VALUES(total_kcal),
                     total_protein_g = VALUES(total_protein_g),
                     total_carbs_g = VALUES(total_carbs_g),
                     total_fats_g = VALUES(total_fats_g),
                     total_fiber_g = VALUES(total_fiber_g),
                     total_sugar_g = VALUES(total_sugar_g),
                     total_sodium_mg = VALUES(total_sodium_mg),
                     avg_health_score = VALUES(avg_health_score),
                     meal_count = VALUES(meal_count),
                     last_recomputed_at_utc = VALUES(last_recomputed_at_utc),
                     updated_at_utc = VALUES(updated_at_utc);

-- ------------------------------------------------------------
-- 2) Water fake data
-- Requested total water range: 2000 ~ 3500 ml
-- ------------------------------------------------------------
INSERT INTO user_water_daily (
    user_id,
    local_date,
    cups,
    ml,
    fl_oz,
    updated_at
)
SELECT
    @user_id,
    x.local_date,
    x.cups,
    x.ml AS ml,
    x.fl_oz AS fl_oz,
    UTC_TIMESTAMP()
FROM (
         SELECT
             water_source.local_date,
             water_source.ml,
             ROUND(water_source.ml / 237) AS cups,
             ROUND(water_source.ml / 29.5735) AS fl_oz
         FROM (
                  SELECT
                      d.local_date,
                      2000 + MOD(d.rn * 53 + d.day_offset * 17, 1501) AS ml
                  FROM tmp_progress_50_days d
              ) water_source
     ) x
ON DUPLICATE KEY UPDATE
                     cups = VALUES(cups),
                     ml = VALUES(ml),
                     fl_oz = VALUES(fl_oz),
                     updated_at = VALUES(updated_at);

-- ------------------------------------------------------------
-- 3) Daily activity fake data
--
-- Important:
--   user_daily_activity.ingest_source is mapped to Java enum:
--   HEALTH_CONNECT, MANUAL, IMPORT.
--   Use IMPORT for DEV SQL fake imported activity data.
-- ------------------------------------------------------------
INSERT INTO user_daily_activity (
    user_id,
    local_date,
    timezone,
    day_start_utc,
    day_end_utc,
    steps,
    active_kcal,
    ingest_source,
    data_origin_package,
    data_origin_name,
    ingested_at,
    updated_at
)
SELECT
    @user_id,
    d.local_date,
    @timezone,
    TIMESTAMP(d.local_date, '00:00:00') - INTERVAL 8 HOUR AS day_start_utc,
    TIMESTAMP(DATE_ADD(d.local_date, INTERVAL 1 DAY), '00:00:00') - INTERVAL 8 HOUR AS day_end_utc,

    -- 4000 ~ 10000 steps
    4000 + MOD(d.rn * 431 + d.day_offset * 113, 6001) AS steps,

    -- 20 ~ 80 active kcal.
    -- Total exercise burn is controlled in workout_session + summary as 100 ~ 500 kcal.
    20 + MOD(d.rn * 17 + d.day_offset * 9, 61) AS active_kcal,

    'IMPORT',
    'com.calai.bitecal.dev',
    'DEV SQL random 50 fake data',
    UTC_TIMESTAMP(),
    UTC_TIMESTAMP()
FROM tmp_progress_50_days d
ON DUPLICATE KEY UPDATE
                     timezone = VALUES(timezone),
                     day_start_utc = VALUES(day_start_utc),
                     day_end_utc = VALUES(day_end_utc),
                     steps = VALUES(steps),
                     active_kcal = VALUES(active_kcal),
                     ingest_source = VALUES(ingest_source),
                     data_origin_package = VALUES(data_origin_package),
                     data_origin_name = VALUES(data_origin_name),
                     updated_at = VALUES(updated_at);

-- ------------------------------------------------------------
-- 4) Workout session fake data
-- One workout session per selected date.
-- ------------------------------------------------------------
INSERT INTO workout_session (
    user_id,
    dictionary_id,
    minutes,
    kcal,
    started_at,
    local_date,
    timezone,
    created_at
)
SELECT
    @user_id,
    @walking_dictionary_id,

    -- 15 ~ 75 minutes
    15 + MOD(d.rn * 7 + d.day_offset * 3, 61) AS minutes,

    -- Workout kcal is generated so that workout_kcal + active_kcal = 100 ~ 500 total burned kcal.
    (
        100 + MOD(d.rn * 29 + d.day_offset * 17, 401)
        ) - (
        20 + MOD(d.rn * 17 + d.day_offset * 9, 61)
        ) AS kcal,

    -- UTC 04:00, displays as 12:00 in Asia/Taipei.
    TIMESTAMP(d.local_date, '04:00:00') AS started_at,
    d.local_date,
    @timezone,
    UTC_TIMESTAMP(6)
FROM tmp_progress_50_days d;

-- ------------------------------------------------------------
-- 5) Workout daily summary fake data
-- This matches:
--   workout_session.kcal + user_daily_activity.active_kcal
-- ------------------------------------------------------------
INSERT INTO user_daily_workout_summary (
    user_id,
    local_date,
    timezone,
    workout_kcal,
    activity_kcal,
    total_burned_kcal,
    workout_session_count,
    last_recomputed_at_utc,
    created_at_utc,
    updated_at_utc
)
SELECT
    @user_id,
    d.local_date,
    @timezone,
    COALESCE(ws.workout_kcal, 0) AS workout_kcal,
    COALESCE(a.active_kcal, 0) AS activity_kcal,
    COALESCE(ws.workout_kcal, 0) + COALESCE(a.active_kcal, 0) AS total_burned_kcal,
    COALESCE(ws.session_count, 0) AS workout_session_count,
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
FROM tmp_progress_50_days d
         LEFT JOIN (
    SELECT
        user_id,
        local_date,
        SUM(kcal) AS workout_kcal,
        COUNT(*) AS session_count
    FROM workout_session
    WHERE user_id = @user_id
      AND local_date BETWEEN @range_start AND @today
    GROUP BY user_id, local_date
) ws
                   ON ws.user_id = @user_id
                       AND ws.local_date = d.local_date
         LEFT JOIN user_daily_activity a
                   ON a.user_id = @user_id
                       AND a.local_date = d.local_date
ON DUPLICATE KEY UPDATE
                     timezone = VALUES(timezone),
                     workout_kcal = VALUES(workout_kcal),
                     activity_kcal = VALUES(activity_kcal),
                     total_burned_kcal = VALUES(total_burned_kcal),
                     workout_session_count = VALUES(workout_session_count),
                     last_recomputed_at_utc = VALUES(last_recomputed_at_utc),
                     updated_at_utc = VALUES(updated_at_utc);

COMMIT;

SET SQL_SAFE_UPDATES = @old_sql_safe_updates;

-- ============================================================
-- Verification 1:
-- Every table should have 50 rows inside selected random dates.
--
-- Important:
--   These are separate SELECT statements to avoid MySQL Error 1137:
--   "Can't reopen table: tmp_progress_50_days".
-- ============================================================

SELECT
    'selected_random_dates' AS table_name,
    COUNT(*) AS rows_count,
    MIN(local_date) AS min_date,
    MAX(local_date) AS max_date
FROM tmp_progress_50_days;

SELECT
    'nutrition' AS table_name,
    COUNT(*) AS rows_count,
    MIN(n.local_date) AS min_date,
    MAX(n.local_date) AS max_date
FROM user_daily_nutrition_summary n
         JOIN tmp_progress_50_days d
              ON d.local_date = n.local_date
WHERE n.user_id = @user_id;

SELECT
    'water' AS table_name,
    COUNT(*) AS rows_count,
    MIN(w.local_date) AS min_date,
    MAX(w.local_date) AS max_date
FROM user_water_daily w
         JOIN tmp_progress_50_days d
              ON d.local_date = w.local_date
WHERE w.user_id = @user_id;

SELECT
    'activity' AS table_name,
    COUNT(*) AS rows_count,
    MIN(a.local_date) AS min_date,
    MAX(a.local_date) AS max_date
FROM user_daily_activity a
         JOIN tmp_progress_50_days d
              ON d.local_date = a.local_date
WHERE a.user_id = @user_id;

SELECT
    'workout_session' AS table_name,
    COUNT(*) AS rows_count,
    MIN(ws.local_date) AS min_date,
    MAX(ws.local_date) AS max_date
FROM workout_session ws
         JOIN tmp_progress_50_days d
              ON d.local_date = ws.local_date
WHERE ws.user_id = @user_id;

SELECT
    'workout_summary' AS table_name,
    COUNT(*) AS rows_count,
    MIN(s.local_date) AS min_date,
    MAX(s.local_date) AS max_date
FROM user_daily_workout_summary s
         JOIN tmp_progress_50_days d
              ON d.local_date = s.local_date
WHERE s.user_id = @user_id;

-- ============================================================
-- Verification 2:
-- Check nutrition ranges.
-- ============================================================

SELECT
    MIN(n.total_kcal) AS min_kcal,
    MAX(n.total_kcal) AS max_kcal,
    MIN(n.total_protein_g) AS min_protein,
    MAX(n.total_protein_g) AS max_protein,
    MIN(n.total_carbs_g) AS min_carbs,
    MAX(n.total_carbs_g) AS max_carbs,
    MIN(n.total_fats_g) AS min_fats,
    MAX(n.total_fats_g) AS max_fats,
    MIN(n.total_sugar_g) AS min_sugar,
    MAX(n.total_sugar_g) AS max_sugar,
    MIN(n.total_fiber_g) AS min_fiber,
    MAX(n.total_fiber_g) AS max_fiber,
    MIN(n.total_sodium_mg) AS min_sodium,
    MAX(n.total_sodium_mg) AS max_sodium,
    MIN(n.avg_health_score) AS min_avg_health_score,
    MAX(n.avg_health_score) AS max_avg_health_score
FROM user_daily_nutrition_summary n
         JOIN tmp_progress_50_days d
              ON d.local_date = n.local_date
WHERE n.user_id = @user_id;

SELECT
    MIN(w.ml) AS min_water_ml,
    MAX(w.ml) AS max_water_ml,
    MIN(a.steps) AS min_steps,
    MAX(a.steps) AS max_steps,
    MIN(s.total_burned_kcal) AS min_total_burned_kcal,
    MAX(s.total_burned_kcal) AS max_total_burned_kcal
FROM tmp_progress_50_days d
         LEFT JOIN user_water_daily w
                   ON w.user_id = @user_id
                       AND w.local_date = d.local_date
         LEFT JOIN user_daily_activity a
                   ON a.user_id = @user_id
                       AND a.local_date = d.local_date
         LEFT JOIN user_daily_workout_summary s
                   ON s.user_id = @user_id
                       AND s.local_date = d.local_date;

-- ============================================================
-- Verification 3:
-- Check distinct value counts for main chart fields.
--
-- Note:
--   Some fields may naturally repeat because their ranges are smaller than 50.
--   Example: fats 45~90 has only 46 possible values.
-- ============================================================

SELECT
    'nutrition_distinct' AS check_name,
    COUNT(*) AS rows_count,
    COUNT(DISTINCT n.local_date) AS distinct_dates,
    COUNT(DISTINCT n.total_kcal) AS distinct_kcal,
    COUNT(DISTINCT n.total_protein_g) AS distinct_protein,
    COUNT(DISTINCT n.total_carbs_g) AS distinct_carbs,
    COUNT(DISTINCT n.total_fats_g) AS distinct_fats,
    COUNT(DISTINCT n.total_sugar_g) AS distinct_sugar,
    COUNT(DISTINCT n.total_fiber_g) AS distinct_fiber,
    COUNT(DISTINCT n.total_sodium_mg) AS distinct_sodium,
    COUNT(DISTINCT n.avg_health_score) AS distinct_avg_health_score
FROM user_daily_nutrition_summary n
         JOIN tmp_progress_50_days d
              ON d.local_date = n.local_date
WHERE n.user_id = @user_id;

-- ============================================================
-- Verification 4:
-- Preview final random data by date.
-- ============================================================

SELECT
    d.local_date,
    n.total_kcal,
    n.total_protein_g,
    n.total_carbs_g,
    n.total_fats_g,
    n.total_fiber_g,
    n.total_sugar_g,
    n.total_sodium_mg,
    n.avg_health_score,
    n.meal_count,
    w.cups AS water_cups,
    w.ml AS water_ml,
    w.fl_oz AS water_fl_oz,
    a.steps,
    a.active_kcal,
    ws.minutes AS workout_minutes,
    ws.kcal AS workout_session_kcal,
    s.workout_kcal,
    s.activity_kcal,
    s.total_burned_kcal
FROM tmp_progress_50_days d
         LEFT JOIN user_daily_nutrition_summary n
                   ON n.user_id = @user_id
                       AND n.local_date = d.local_date
         LEFT JOIN user_water_daily w
                   ON w.user_id = @user_id
                       AND w.local_date = d.local_date
         LEFT JOIN user_daily_activity a
                   ON a.user_id = @user_id
                       AND a.local_date = d.local_date
         LEFT JOIN workout_session ws
                   ON ws.user_id = @user_id
                       AND ws.local_date = d.local_date
         LEFT JOIN user_daily_workout_summary s
                   ON s.user_id = @user_id
                       AND s.local_date = d.local_date
ORDER BY d.local_date DESC;
