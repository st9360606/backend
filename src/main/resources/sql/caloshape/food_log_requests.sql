CREATE TABLE IF NOT EXISTS food_log_requests
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    food_log_id     CHAR(36)     NULL,
    status          VARCHAR(16)  NOT NULL,  -- RESERVED/ATTACHED/FAILED
    created_at_utc  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at_utc  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_food_log_requests_user_req (user_id, request_id),
    INDEX idx_food_log_requests_log (food_log_id)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
