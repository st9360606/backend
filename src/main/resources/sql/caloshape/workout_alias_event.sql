-- ============================================================
-- Table: workout_alias_event
-- Purpose:
-- 1. Store append-only workout alias usage events.
-- 2. Track user-submitted or matched workout phrases.
-- 3. Support alias analytics and future alias dictionary improvement.
-- 4. Preserve match confidence and generic fallback usage.
-- ============================================================

CREATE TABLE IF NOT EXISTS workout_alias_event
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,

    user_id         BIGINT        NOT NULL,

    lang_tag        VARCHAR(16)   NOT NULL,

    -- Lowercased normalized phrase submitted or matched by the user.
    phrase_lower    VARCHAR(256)  NOT NULL,

    matched_dict_id BIGINT        NULL,

    -- Match confidence score.
    score           DECIMAL(6, 5) NULL,

    -- Whether generic fallback matching was used.
    used_generic    TINYINT(1)    NOT NULL DEFAULT 0,

    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    INDEX idx_event_created_at
        (created_at),

    INDEX idx_event_lang_phrase
        (lang_tag, phrase_lower),

    INDEX idx_alias_event_user_created_at
        (user_id, created_at),

    CONSTRAINT fk_event_dict
        FOREIGN KEY (matched_dict_id)
            REFERENCES workout_dictionary (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
