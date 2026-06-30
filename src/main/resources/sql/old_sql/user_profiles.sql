-- user_profiles：每位 user 一筆註冊檔（含公制＋英制欄位 + daily_step_goal）
CREATE TABLE IF NOT EXISTS user_profiles
(
    user_id           BIGINT        NOT NULL PRIMARY KEY,
    gender            VARCHAR(16)   NULL,
    age               INT           NULL,

    -- 身高（公制＋英制）
    height_cm         DECIMAL(5, 2) NULL,
    height_feet       SMALLINT      NULL,
    height_inches     SMALLINT      NULL,

    -- 體重（公制＋英制）
    weight_kg         DECIMAL(5, 2) NULL,
    weight_lbs        DECIMAL(5, 2) NULL,

    exercise_level    VARCHAR(32)   NULL,
    goal              VARCHAR(32)   NULL,

    -- 目標步數（整合：NOT NULL + DEFAULT）
    daily_step_goal   INT           NOT NULL DEFAULT 10000,

    -- 目標體重（公制＋英制）
    goal_weight_kg  DECIMAL(5, 2) NULL,
    goal_weight_lbs DECIMAL(5, 2) NULL,

    referral_source   VARCHAR(64)   NULL,
    locale            VARCHAR(16)   NULL,
    timezone          VARCHAR(64)   NULL,

    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

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

    -- daily_step_goal 已 NOT NULL，因此不用再寫 "IS NULL OR"
    CONSTRAINT chk_daily_step_goal_range
        CHECK (daily_step_goal BETWEEN 0 AND 200000)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;




-- MySQL / InnoDB
ALTER TABLE user_profiles
    -- 使用者偏好單位（KG/LBS）
    ADD COLUMN unit_preference VARCHAR(8) NOT NULL DEFAULT 'KG' AFTER goal_weight_lbs,

    -- 每週運動次數（0..7）
    ADD COLUMN workouts_per_week TINYINT NULL AFTER unit_preference,

    -- 熱量與宏量（計算結果）
    ADD COLUMN kcal INT NOT NULL DEFAULT 0 AFTER workouts_per_week,
    ADD COLUMN protein_g INT NOT NULL DEFAULT 0 AFTER kcal,
    ADD COLUMN carbs_g INT NOT NULL DEFAULT 0 AFTER protein_g,
    ADD COLUMN fat_g INT NOT NULL DEFAULT 0 AFTER carbs_g,
    ADD COLUMN water_ml INT NOT NULL DEFAULT 0 AFTER fat_g,

    -- BMI 與分級（計算結果）
    ADD COLUMN bmi DECIMAL(5,2) NOT NULL DEFAULT 0.00 AFTER water_ml,
    ADD COLUMN bmi_class VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER bmi,

    -- 計算版本（方便未來你調公式）
    ADD COLUMN calc_version VARCHAR(32) NOT NULL DEFAULT 'healthcalc_v1' AFTER bmi_class;

-- （可選）MySQL 8+ 才比較可靠：加 CHECK
ALTER TABLE user_profiles
    ADD CONSTRAINT chk_workouts_per_week_range
        CHECK (workouts_per_week IS NULL OR workouts_per_week BETWEEN 0 AND 7),
    ADD CONSTRAINT chk_plan_kcal_nonneg CHECK (kcal >= 0),
    ADD CONSTRAINT chk_plan_macros_nonneg CHECK (carbs_g >= 0 AND protein_g >= 0 AND fat_g >= 0),
    ADD CONSTRAINT chk_plan_water_nonneg CHECK (water_ml >= 0);

ALTER TABLE user_profiles
    ADD COLUMN plan_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' AFTER bmi_class;

ALTER TABLE user_profiles
    ADD COLUMN water_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO'AFTER water_ml;

/**
  公式 1) 糖（Free sugars） WHO：應降到 世界衛生組織 10% 上限（一般建議）
  <10% 總熱量；再降到
  <5% 會有額外健康益處。
  SugarG = TDEE_kcal × 0.10 ÷ 4

  2) 纖維（Dietary fibre） USDA 近年公開資料常見建議：成人最好 建議攝取 35 克/日，並偏好「食物中天然存在」的纖維。

  3) 鈉（Sodium） USDA：成人建議 鈉上限 sodiumMaxMg = 2,300 毫克以下
 */
ALTER TABLE user_profiles
    ADD COLUMN fiber_g          INTEGER NOT NULL DEFAULT 35 AFTER fat_g,
    ADD COLUMN sugar_g INTEGER NOT NULL DEFAULT 0 AFTER fiber_g,
    ADD COLUMN sodium_mg        INTEGER NOT NULL DEFAULT 2300 AFTER sugar_g;

CREATE INDEX idx_user_profiles_timezone ON user_profiles (timezone);


ALTER TABLE user_profiles
    ADD COLUMN daily_workout_goal_kcal INT NOT NULL DEFAULT 450 AFTER workouts_per_week;
