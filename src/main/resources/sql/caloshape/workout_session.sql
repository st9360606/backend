-- ============================================================
-- workout_session
--
-- 使用者實際的一次運動紀錄（一筆 session）
--
-- 設計重點：
-- 1. started_at   : UTC 絕對時間，用於排序 / today 範圍查詢 / 顯示時間
-- 2. local_date   : 建立當下依使用者時區切出的本地日期，用於 daily bucket 固化
-- 3. timezone     : 建立當下使用的時區 ID（例如 Asia/Taipei）
--
-- 這樣可避免：
-- - 使用者日後換時區
-- - request header 傳錯
-- - delete/recompute 時回推錯誤 bucket
--
-- Google Play 健康/隱私合規：
-- - session 可刪除
-- - kcal 僅為 estimated calories burned，不是醫療宣稱
-- ============================================================

CREATE TABLE IF NOT EXISTS workout_session
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,

    user_id       BIGINT      NOT NULL,
    dictionary_id BIGINT      NOT NULL,

    minutes       INT         NOT NULL,
    kcal          INT         NOT NULL,

    -- Actual UTC timestamp of the workout session.
    started_at    DATETIME(6) NOT NULL,

    -- User-local date calculated at creation time.
    -- Used as a stable daily bucket for summary/recompute.
    local_date    DATE        NOT NULL,

    -- IANA timezone used when creating this session.
    -- Example: Asia/Taipei
    timezone      VARCHAR(64) NOT NULL,

    -- Row creation time in UTC.
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    -- History / today range / absolute-time ordering.
    INDEX idx_workout_session_user_started_at
        (user_id, started_at),

    -- Daily summary / weekly progress / delete-recompute / local-date ordering.
    INDEX idx_workout_session_user_local_date_started_at
        (user_id, local_date, started_at),

    CONSTRAINT fk_workout_session_dictionary
        FOREIGN KEY (dictionary_id)
            REFERENCES workout_dictionary (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
