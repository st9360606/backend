/* ============================================================
 * Workout Tracking Schema
 *
 * 功能：
 *  - workout_dictionary : 標準化的運動字典（走路、跑步、騎腳踏車...）
 *  - workout_alias      : 使用者實際輸入的各種語言/別名 → map 到字典
 *  - workout_session    : 使用者一天內實際做了什麼運動、花幾分鐘、估計消耗多少 kcal
 *
 * 注意：
 *  - 我們不在 DB 裡存「歸屬哪一天(當地日曆)」，而是存 UTC-ish 時間戳 (started_at)
 *    前端呼叫 API 時會帶 X-Client-Timezone，後端依那個時區去切 LocalDate.now(zone)
 *    來決定「今天」是什麼範圍。這邏輯在 Service.buildToday() 已處理。
 *
 *  - kcal 的說明文字在 App 會顯示 "estimated calories burned"
 *    不做醫療/療效宣稱，符合 Google Play 健康資料合規要求。
 *
 * Flyway / 本地開發用 NOTE：
 *  - DROP TABLE ... IF EXISTS 僅建議在本地/測試用。上正式環境時請拿掉 DROP。
 * ============================================================
 */

-- ============================================================
-- 開發環境方便重建（正式環境請移除 DROP）
-- ============================================================
DROP TABLE IF EXISTS workout_session;
DROP TABLE IF EXISTS workout_alias;
DROP TABLE IF EXISTS workout_dictionary;

-- ============================================================
-- 1. workout_dictionary
--
--    這是權威運動清單 (canonical list)，例如：
--      - canonical_key   = 'walking'
--      - display_name_en = 'Walking'
--      - met_value       = 3.5        (MET: 代謝當量)
--      - icon_key        = 'walk'     (前端可根據這個顯示正確 icon)
--
--    kcal 計算公式 (後端):
--        kcal = MET * 使用者體重(kg) * (minutes / 60.0)
--
--    這張表通常是後台/營運在維護，不讓一般使用者直接改。
-- ============================================================
CREATE TABLE workout_dictionary
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 唯一識別 key，用來當後台/工程師的穩定 ID
    canonical_key   VARCHAR(64)  NOT NULL UNIQUE,

    -- 英文顯示名稱（目前先用英文顯示給所有語言，之後可擴充 i18n）
    display_name_en VARCHAR(128) NOT NULL,

    -- MET 值，用來估算消耗熱量
    met_value       DOUBLE       NOT NULL,

    -- 前端用來挑 icon，例如 "walk", "run", "bike"
    icon_key        VARCHAR(32)  NOT NULL,

    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- ============================================================
-- 2. workout_alias
--
--    目標：解決「多國語言自由輸入」→ 辨識成字典的一個運動。
--
--    例如使用者輸入：
--      "15 min nằm đẩy tạ"
--      "30分鐘 散步"
--      "45 min cycling"
--
--    我們會把非時間的那段動作字串轉小寫後丟進 alias：
--      phrase_lower = "nằm đẩy tạ"
--      lang_tag     = "vi-VN"
--
--    狀態：
--      - APPROVED: 管理員已經審核，並且綁定到 dictionary_id
--      - PENDING : 剛新增，還沒審核，不一定有 dictionary_id
--      - REJECTED: 拒絕 (色情字、亂鍵盤、惡搞字串)
--
--    前端呼叫 /estimate：
--      - 如果能 map 到 APPROVED alias → status="ok"，直接算 kcal
--      - 否則：
--          a) 我們自動插一筆 PENDING (dictionary_id=NULL)
--          b) 回傳 status="not_found"，前端顯示 Scan Failed 畫面 (7.jpg)
--
--    這可以長期學習使用者輸入，但「別名生效」一定要人工審核才能 APPROVED，
--    避開濫用、避免色情/仇恨字眼直接進公共字典（這對審核/法規非常重要）。
-- ============================================================
CREATE TABLE workout_alias
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 指向字典。PENDING 可能還沒對到某個標準運動，所以允許 NULL
    dictionary_id   BIGINT                                 NULL,

    -- 使用者語系 (ex: 'zh-TW', 'en', 'vi-VN', 'ja-JP')
    lang_tag        VARCHAR(16)                            NOT NULL,

    -- 使用者實際輸入行為字串的「小寫版」
    -- 後端在存的時候就應該 toLowerCase(Locale.ROOT)
    phrase_lower    VARCHAR(128)                           NOT NULL,

    -- 審核狀態
    status          ENUM ('APPROVED','PENDING','REJECTED') NOT NULL DEFAULT 'PENDING',

    -- 哪個使用者 first 提供了這個 phrase（做審核追蹤用，之後也可以用來反濫用）
    created_by_user BIGINT                                 NULL,

    created_at      TIMESTAMP                              NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_alias_dict
        FOREIGN KEY (dictionary_id)
            REFERENCES workout_dictionary (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT
);

-- 幫助 /estimate 找 alias，用 phrase_lower + lang_tag + status 快速定位
CREATE INDEX idx_alias_phrase
    ON workout_alias (phrase_lower, lang_tag, status);


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




-- 字典（若已存在則略過或更新）
-- 建議確保有唯一鍵：PRIMARY KEY(id) 以及 UNIQUE(canonical_key)
INSERT INTO workout_dictionary (id, canonical_key, display_name_en, met_value, icon_key)
    VALUES
        (1,  'walking',            'Walking',            3.5, 'walk'),
        (2,  'running',            'Running',            9.0, 'run'),
        (3,  'cycling',            'Cycling',            8.0, 'bike'),
        (4,  'swimming',           'Swimming',           8.0, 'swimming'),
        (5,  'hiking',             'Hiking',             6.0, 'hiking'),
        (6,  'aerobic_exercise',   'Aerobic exercise',   8.0, 'aerobic_exercise'),
        (7,  'strength',           'Strength Training',  4.0, 'strength'),
        (8,  'weight_training',    'Weight training',    6.0, 'weight_training'),
        (9,  'basketball',         'Basketball',         8.0, 'basketball'),
        (10, 'soccer',             'Soccer',             8.0, 'soccer'),
        (11, 'tennis',             'Tennis',             7.3, 'tennis'),
        (12, 'yoga',               'Yoga',               3.0, 'yoga')
        AS new
ON DUPLICATE KEY UPDATE
                     canonical_key   = new.canonical_key,
                     display_name_en = new.display_name_en,
                     met_value       = new.met_value,
                     icon_key        = new.icon_key;
