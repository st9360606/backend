-- ============================================================
-- 3. workout_session
--
--    使用者實際的一次運動紀錄 (一筆 session)。
--
--    欄位：
--      user_id        : 哪個使用者
--      dictionary_id  : 這次被歸類成哪個標準運動 (ex: Walking)
--      minutes        : 持續多久
--      kcal           : 這次「estimated calories burned」
--      started_at     : 這筆運動被紀錄的時間點 (Instant.now() 轉 TIMESTAMP)
--
--    為什麼不直接存「date」？
--      - 因為「今天是幾號」要看使用者當下的時區。
--        後端在 /today 會用 X-Client-Timezone，把當地 LocalDate
--        映射成 UTC 範圍 [start,end)，再去撈 session。
--
--    這樣就能正確處理：
--      - 23:30 的運動，23:35 記錄 ⇒ 落在「當地日期的今天」
--      - 過午夜 00:10 再記錄 ⇒ 自動算入明天
--      - 飛到另一個時區 ⇒ 換新的 dayLocal，不會把卡路里塞錯日
--
--    Google Play 健康/隱私合規：
--      - /api/v1/workouts/{sessionId} 支援 DELETE
--        → 使用者可以刪掉這筆 session
--      - kcal 顯示是「estimated calories burned」，不是醫療宣稱
-- ============================================================
CREATE TABLE workout_session
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 哪個使用者的紀錄
    user_id       BIGINT    NOT NULL,

    -- 參照哪個標準運動
    dictionary_id BIGINT    NOT NULL,

    -- 這次做了多久
    minutes       INT       NOT NULL,

    -- 預估消耗熱量 (kcal)
    kcal          INT       NOT NULL,

    -- 這筆 session 的起始時間點
    -- 後端目前用 Instant.now() -> TIMESTAMP
    -- 注意：這是 UTC-ish 絕對時間戳，後端會再依時區去做 LocalDate 切日
    started_at    TIMESTAMP NOT NULL,

    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_session_dict
        FOREIGN KEY (dictionary_id)
            REFERENCES workout_dictionary (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT
);

-- 幫 /today 這類 API 查「這個 user 在 [start,end) 期間的紀錄」
CREATE INDEX idx_user_time
    ON workout_session (user_id, started_at);




