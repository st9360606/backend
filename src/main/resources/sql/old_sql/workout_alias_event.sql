-- 事件表（append-only）
CREATE TABLE workout_alias_event
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

-- 建索引：idx_alias_event_user_created
SET @exists := (SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'workout_alias_event'
                  AND index_name = 'idx_alias_event_user_created');
SET @sql := IF(@exists = 0,
               'CREATE INDEX idx_alias_event_user_created ON workout_alias_event (user_id, created_at)',
               'SELECT "idx_alias_event_user_created exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;



