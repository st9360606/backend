CREATE TABLE user_water_daily
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    BIGINT UNSIGNED NOT NULL,
    local_date DATE      NOT NULL,                             -- 使用者當地的「日曆日」，例如 2025-10-27
    cups       INT UNSIGNED NOT NULL DEFAULT 0,                -- 幾杯
    ml         INT UNSIGNED NOT NULL DEFAULT 0,                -- 總 ml
    fl_oz      INT UNSIGNED NOT NULL DEFAULT 0,                -- 總 fl oz
    updated_at TIMESTAMP NOT NULL
        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, -- 自動更新最後修改時間
    PRIMARY KEY (id),

    -- 同一個 user 在同一個日曆日只會有一筆
    UNIQUE KEY uq_user_water_daily_user_date (user_id, local_date),

    -- 幫助我們用日期清理舊資料
    KEY        idx_user_water_daily_local_date (local_date)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
