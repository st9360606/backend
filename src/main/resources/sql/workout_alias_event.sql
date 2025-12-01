-- 事件表（append-only）
CREATE TABLE IF NOT EXISTS workout_alias_event
(
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    lang_tag        VARCHAR(16)   NOT NULL,
    phrase_lower    VARCHAR(256)  NOT NULL,
    matched_dict_id BIGINT        NULL,
    score           DECIMAL(6, 5) NULL,
    used_generic    TINYINT(1)    NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_dict FOREIGN KEY (matched_dict_id) REFERENCES workout_dictionary (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_event_created_at ON workout_alias_event (created_at);
CREATE INDEX idx_event_lang_phrase ON workout_alias_event (lang_tag, phrase_lower);
CREATE INDEX idx_alias_event_user_created_at ON workout_alias_event (user_id, created_at);

-- UserProfileRepository 查時區用欄位 timezone（請先確保欄位存在）
-- 若尚未有 timezone 欄位，可先新增並以 'UTC' 或伺服器預設填值
ALTER TABLE user_profiles
    ADD COLUMN timezone VARCHAR(64) NULL AFTER locale;

CREATE INDEX idx_user_profiles_timezone ON user_profiles (timezone);




