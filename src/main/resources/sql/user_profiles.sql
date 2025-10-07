-- user_profiles：每位 user 一筆註冊檔
CREATE TABLE IF NOT EXISTS user_profiles (
                                             user_id           BIGINT      NOT NULL PRIMARY KEY,
                                             gender            VARCHAR(16) NULL,
    age               INT         NULL,
    height_cm         DECIMAL(5,2) NULL,
    weight_kg         DECIMAL(5,2) NULL,
    exercise_level    VARCHAR(32) NULL,    -- e.g., sedentary/light/moderate/active
    goal              VARCHAR(32) NULL,    -- e.g., lose/maintain/gain
    target_weight_kg  DECIMAL(5,2) NULL,
    referral_source   VARCHAR(64) NULL,
    locale            VARCHAR(16) NULL,
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
