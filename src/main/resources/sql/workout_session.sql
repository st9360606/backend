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

CREATE TABLE workout_session
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    dictionary_id   BIGINT       NOT NULL,
    minutes         INT          NOT NULL,
    kcal            INT          NOT NULL,

    -- 真正記錄當下的 UTC 時間點（由後端 Clock / Instant 寫入）
    started_at      DATETIME(6)  NOT NULL,

    -- 建立這筆 session 當下，以使用者時區切出的本地日期
    -- 例：started_at = 2026-04-14T15:30:00Z，timezone = Asia/Taipei
    -- 則 local_date = 2026-04-14
    local_date      DATE         NOT NULL,

    -- 建立這筆 session 時採用的時區 ID
    timezone        VARCHAR(64)  NOT NULL,

    -- row 建立時間（UTC）
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_workout_session_dictionary
        FOREIGN KEY (dictionary_id)
            REFERENCES workout_dictionary (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- Indexes
-- ============================================================

-- today / history / 排序查詢
CREATE INDEX idx_workout_session_user_started_at
    ON workout_session (user_id, started_at);

-- summary / weekly progress / delete-recompute / daily aggregate
CREATE INDEX idx_workout_session_user_local_date
    ON workout_session (user_id, local_date);

-- 若未來有依 local_date + started_at 做排序，也可保留這個複合索引
CREATE INDEX idx_workout_session_user_local_date_started_at
    ON workout_session (user_id, local_date, started_at);