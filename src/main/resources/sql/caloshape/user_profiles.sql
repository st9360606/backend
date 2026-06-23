-- ============================================================
-- Table: user_profiles
-- Purpose:
-- 1. Store one profile row per user.
-- 2. Preserve metric and imperial onboarding values.
-- 3. Store calculated nutrition, water, BMI, and activity goals.
-- 4. Support user-local timezone and locale preferences.
-- ============================================================

CREATE TABLE IF NOT EXISTS user_profiles
(
    user_id                 BIGINT        NOT NULL,

    gender                  VARCHAR(16)   NULL,
    age                     INT           NULL,

    -- Height: metric + imperial.
    height_cm               DECIMAL(5, 2) NULL,
    height_feet             SMALLINT      NULL,
    height_inches           SMALLINT      NULL,

    -- Weight: metric + imperial.
    weight_kg               DECIMAL(5, 2) NULL,
    weight_lbs              DECIMAL(5, 2) NULL,

    exercise_level          VARCHAR(32)   NULL,
    workouts_per_week       TINYINT       NULL,

    -- Daily workout calorie goal.
    daily_workout_goal_kcal INT           NOT NULL DEFAULT 450,

    goal                    VARCHAR(32)   NULL,

    -- Daily step goal.
    daily_step_goal         INT           NOT NULL DEFAULT 10000,

    -- Goal weight: metric + imperial.
    goal_weight_kg          DECIMAL(5, 2) NULL,
    goal_weight_lbs         DECIMAL(5, 2) NULL,

    -- User preferred body-weight unit: KG / LBS.
    unit_preference         VARCHAR(8)    NOT NULL DEFAULT 'KG',

    -- Nutrition plan calculation result.
    kcal                    INT           NOT NULL DEFAULT 0,
    protein_g               INT           NOT NULL DEFAULT 0,
    carbs_g                 INT           NOT NULL DEFAULT 0,
    fat_g                   INT           NOT NULL DEFAULT 0,

    -- Micronutrient goals.
    fiber_g                 INT           NOT NULL DEFAULT 35,
    sugar_g                 INT           NOT NULL DEFAULT 0,
    sodium_mg               INT           NOT NULL DEFAULT 2300,

    -- Water plan calculation result.
    water_ml                INT           NOT NULL DEFAULT 0,
    water_mode              VARCHAR(16)   NOT NULL DEFAULT 'AUTO',

    -- BMI calculation result.
    bmi                     DECIMAL(5, 2) NOT NULL DEFAULT 0.00,
    bmi_class               VARCHAR(16)   NOT NULL DEFAULT 'UNKNOWN',

    -- Plan mode: AUTO / MANUAL.
    plan_mode               VARCHAR(16)   NOT NULL DEFAULT 'AUTO',

    -- Calculation version for future formula migration.
    calc_version            VARCHAR(32)   NOT NULL DEFAULT 'healthcalc_v1',

    referral_source         VARCHAR(64)   NULL,
    locale                  VARCHAR(16)   NULL,
    timezone                VARCHAR(64)   NULL,

    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id),

    INDEX idx_user_profiles_timezone
        (timezone),

    CONSTRAINT fk_user_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id),

    CONSTRAINT chk_height_feet_range
        CHECK (height_feet IS NULL OR height_feet BETWEEN 0 AND 8),

    CONSTRAINT chk_height_inches_range
        CHECK (height_inches IS NULL OR height_inches BETWEEN 0 AND 11),

    CONSTRAINT chk_weight_lbs_range
        CHECK (weight_lbs IS NULL OR weight_lbs BETWEEN 40 AND 900),

    CONSTRAINT chk_goal_weight_lbs_range
        CHECK (goal_weight_lbs IS NULL OR goal_weight_lbs BETWEEN 40 AND 900),

    CONSTRAINT chk_daily_step_goal_range
        CHECK (daily_step_goal BETWEEN 0 AND 200000),

    CONSTRAINT chk_workouts_per_week_range
        CHECK (workouts_per_week IS NULL OR workouts_per_week BETWEEN 0 AND 7),

    CONSTRAINT chk_plan_kcal_nonneg
        CHECK (kcal >= 0),

    CONSTRAINT chk_plan_macros_nonneg
        CHECK (carbs_g >= 0 AND protein_g >= 0 AND fat_g >= 0),

    CONSTRAINT chk_plan_water_nonneg
        CHECK (water_ml >= 0)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
