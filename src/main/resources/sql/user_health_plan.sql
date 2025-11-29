-- === user_health_plan: 每個 user 一筆「最新」健康計畫（onboarding/設定更新時覆蓋） ===
CREATE TABLE IF NOT EXISTS user_health_plan (
  user_id           BIGINT       NOT NULL PRIMARY KEY,

  -- meta
  source            VARCHAR(32)   NOT NULL,            -- e.g. ONBOARDING / SETTINGS
  calc_version      VARCHAR(32)   NOT NULL,            -- e.g. healthcalc_v1
  client_timezone   VARCHAR(64)   NULL,

  -- inputs snapshot（方便未來追溯/重算）
  goal_key          VARCHAR(32)   NULL,                -- LOSE/MAINTAIN/GAIN/HEALTHY_EATING
  gender            VARCHAR(16)   NULL,                -- Male/Female（或 MALE/FEMALE）
  age               SMALLINT      NULL,
  height_cm         DECIMAL(5,2)  NULL,
  weight_kg         DECIMAL(5,2)  NULL,
  target_weight_kg  DECIMAL(5,2)  NULL,
  unit_preference   VARCHAR(8)    NOT NULL,            -- KG/LBS
  workouts_per_week TINYINT       NULL,

  -- results（你畫面上顯示的那批）
  kcal              INT          NOT NULL,
  carbs_g           INT          NOT NULL,
  protein_g         INT          NOT NULL,
  fat_g             INT          NOT NULL,
  water_ml          INT          NOT NULL,
  bmi               DECIMAL(5,2) NOT NULL,
  bmi_class         VARCHAR(16)  NOT NULL,             -- Underweight/Normal/Overweight/Obesity

  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  INDEX idx_user_health_plan_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
