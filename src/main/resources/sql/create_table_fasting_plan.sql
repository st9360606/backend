-- V10__create_table_fasting_plan.sql （修正版）
CREATE TABLE IF NOT EXISTS fasting_plan (
                                            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                            user_id     BIGINT NOT NULL,
                                            plan_code   VARCHAR(16) NOT NULL,      -- e.g. '16:8'
    start_time  CHAR(5)  NOT NULL,         -- 'HH:mm'（純字串，無時區換算）
    end_time    CHAR(5)  NOT NULL,         -- 'HH:mm'（由吃窗小時數推得）
    enabled     TINYINT(1) NOT NULL DEFAULT 0,
    time_zone   VARCHAR(64) NOT NULL,      -- e.g. 'Asia/Taipei'
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_fasting_plan_user FOREIGN KEY (user_id) REFERENCES users(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每位用戶僅允許 1 筆設定
CREATE UNIQUE INDEX uk_fasting_plan_user ON fasting_plan(user_id);
