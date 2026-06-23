-- Sample workout_session data for user_id = 1 across the most recent 7 local dates.
-- Requires workout_dictionary rows with ids 1, 2, 3, and 7.
INSERT INTO workout_session
    (user_id, dictionary_id, minutes, kcal, started_at, local_date, timezone, created_at)
VALUES
    (1, 1, 30, 145, CONCAT(DATE_SUB(CURRENT_DATE(), INTERVAL 6 DAY), ' 07:30:00'), DATE_SUB(CURRENT_DATE(), INTERVAL 6 DAY), 'Asia/Taipei', CURRENT_TIMESTAMP(6)),
    (1, 2, 25, 260, CONCAT(DATE_SUB(CURRENT_DATE(), INTERVAL 5 DAY), ' 18:15:00'), DATE_SUB(CURRENT_DATE(), INTERVAL 5 DAY), 'Asia/Taipei', CURRENT_TIMESTAMP(6)),
    (1, 3, 45, 420, CONCAT(DATE_SUB(CURRENT_DATE(), INTERVAL 4 DAY), ' 06:50:00'), DATE_SUB(CURRENT_DATE(), INTERVAL 4 DAY), 'Asia/Taipei', CURRENT_TIMESTAMP(6)),
    (1, 7, 40, 180, CONCAT(DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), ' 19:05:00'), DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), 'Asia/Taipei', CURRENT_TIMESTAMP(6)),
    (1, 1, 35, 168, CONCAT(DATE_SUB(CURRENT_DATE(), INTERVAL 2 DAY), ' 08:20:00'), DATE_SUB(CURRENT_DATE(), INTERVAL 2 DAY), 'Asia/Taipei', CURRENT_TIMESTAMP(6)),
    (1, 2, 30, 312, CONCAT(DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), ' 17:40:00'), DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), 'Asia/Taipei', CURRENT_TIMESTAMP(6)),
    (1, 3, 50, 465, CONCAT(CURRENT_DATE(), ' 07:10:00'), CURRENT_DATE(), 'Asia/Taipei', CURRENT_TIMESTAMP(6));
