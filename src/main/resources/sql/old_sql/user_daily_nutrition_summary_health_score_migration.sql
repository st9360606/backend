ALTER TABLE user_daily_nutrition_summary
    ADD COLUMN avg_health_score DECIMAL(4,1) NOT NULL DEFAULT 0 AFTER total_sodium_mg;

UPDATE user_daily_nutrition_summary s
JOIN (
    SELECT
        user_id,
        captured_local_date AS local_date,
        COALESCE(AVG(
            CASE
                WHEN JSON_EXTRACT(effective, '$.healthScore') IS NULL THEN NULL
                WHEN JSON_TYPE(JSON_EXTRACT(effective, '$.healthScore')) = 'NULL' THEN NULL
                ELSE CAST(JSON_UNQUOTE(JSON_EXTRACT(effective, '$.healthScore')) AS DECIMAL(4,1))
            END
        ), 0) AS avg_health_score
    FROM food_logs
    WHERE status IN ('DRAFT', 'SAVED')
    GROUP BY user_id, captured_local_date
) a ON a.user_id = s.user_id AND a.local_date = s.local_date
SET s.avg_health_score = GREATEST(0, LEAST(10, ROUND(a.avg_health_score, 1)));
