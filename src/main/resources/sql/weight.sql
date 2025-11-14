-- === MySQL Tables ===
CREATE TABLE IF NOT EXISTS weight_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    log_date DATE NOT NULL,
    weight_kg DECIMAL(6,1) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    photo_url TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- (A) 欄位層級自動更新（8.0/5.7 皆可）
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_weight_history_user_date UNIQUE (user_id, log_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weight_timeseries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    log_date DATE NOT NULL,
    weight_kg DECIMAL(6,1) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_weight_timeseries_user_date UNIQUE (user_id, log_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === Generated lbs（以 View 暴露） ===
-- 許多環境較保險：先 DROP 再 CREATE
DROP VIEW IF EXISTS v_weight_history;
CREATE VIEW v_weight_history AS
SELECT
    id, user_id, log_date, weight_kg,
    CAST(ROUND(weight_kg * 2.20462262, 1) AS DECIMAL(6,1)) AS weight_lbs,
    timezone, photo_url, created_at, updated_at
FROM weight_history;

DROP VIEW IF EXISTS v_weight_timeseries;
CREATE VIEW v_weight_timeseries AS
SELECT
    id, user_id, log_date, weight_kg,
    CAST(ROUND(weight_kg * 2.20462262, 1) AS DECIMAL(6,1)) AS weight_lbs,
    timezone, created_at
FROM weight_timeseries;

-- === 觸發器（B 選項）：若你不想用欄位層級 ON UPDATE，就改用下面這段，並把上面欄位的 ON UPDATE 拿掉 ===
-- DROP TRIGGER IF EXISTS trg_touch_wh;
-- CREATE TRIGGER trg_touch_wh
-- BEFORE UPDATE ON weight_history
-- FOR EACH ROW
--   SET NEW.updated_at = CURRENT_TIMESTAMP;

-- === Indexes ===
-- MySQL 忽略索引的升降冪註記，直接建 (user_id, log_date) 即可
CREATE INDEX idx_wh_user_date ON weight_history (user_id, log_date);
CREATE INDEX idx_wt_user_date ON weight_timeseries (user_id, log_date);
