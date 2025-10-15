-- user_profiles：每位 user 一筆註冊檔（含公制＋英制欄位）
CREATE TABLE IF NOT EXISTS user_profiles (
                                             user_id             BIGINT       NOT NULL PRIMARY KEY,
                                             gender              VARCHAR(16)  NULL,
                                             age                 INT          NULL,

    -- 身高（公制＋英制）
                                             height_cm           DECIMAL(5,2) NULL,
                                             height_feet         SMALLINT     NULL,   -- 0..8，允許 NULL（僅英制輸入時存）
                                             height_inches       SMALLINT     NULL,   -- 0..11，允許 NULL

    -- 體重（公制＋英制）
                                             weight_kg           DECIMAL(5,2) NULL,
                                             weight_lbs          INT          NULL,   -- 40..900，允許 NULL

                                             exercise_level      VARCHAR(32)  NULL,   -- sedentary/light/moderate/active/very_active
                                             goal                VARCHAR(32)  NULL,   -- lose/maintain/gain/healthy_eating 等

    -- 目標體重（公制＋英制）
                                             target_weight_kg    DECIMAL(5,2) NULL,
                                             target_weight_lbs   INT          NULL,   -- 40..900，允許 NULL

                                             referral_source     VARCHAR(64)  NULL,
                                             locale              VARCHAR(16)  NULL,

                                             created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                             updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                             CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id)

    -- ✅ MySQL 8.0.16+ 會強制檢查；較舊版本會忽略 CHECK（可接受）
    ,CONSTRAINT chk_height_feet_range          CHECK (height_feet   IS NULL OR height_feet BETWEEN 0 AND 8)
    ,CONSTRAINT chk_height_inches_range        CHECK (height_inches IS NULL OR height_inches BETWEEN 0 AND 11)
    ,CONSTRAINT chk_weight_lbs_range           CHECK (weight_lbs    IS NULL OR weight_lbs BETWEEN 40 AND 900)
    ,CONSTRAINT chk_target_weight_lbs_range    CHECK (target_weight_lbs IS NULL OR target_weight_lbs BETWEEN 40 AND 900)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
