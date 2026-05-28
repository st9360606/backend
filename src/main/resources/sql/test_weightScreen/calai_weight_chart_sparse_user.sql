-- Manual sparse weight chart fixture for user_id = 42.
-- Assumes users.id = 42 already exists. If it does not, create a test user/profile first.
-- The chart reads weight_timeseries; the list below the chart reads weight_history.

INSERT INTO weight_timeseries
    (user_id, log_date, weight_kg, weight_lbs, timezone, created_at)
VALUES
    (42, '2025-06-01', 82.8, 182.5, 'Asia/Taipei', '2025-06-01 08:00:00'),
    (42, '2025-06-30', 82.1, 181.0, 'Asia/Taipei', '2025-06-30 08:00:00'),
    (42, '2025-07-09', 81.7, 180.1, 'Asia/Taipei', '2025-07-09 08:00:00'),
    (42, '2025-12-05', 79.8, 176.0, 'Asia/Taipei', '2025-12-05 08:00:00'),
    (42, '2026-01-01', 78.9, 173.9, 'Asia/Taipei', '2026-01-01 08:00:00'),
    (42, '2026-01-29', 78.2, 172.4, 'Asia/Taipei', '2026-01-29 08:00:00'),
    (42, '2026-01-30', 78.0, 172.0, 'Asia/Taipei', '2026-01-30 08:00:00'),
    (42, '2026-02-28', 77.4, 170.6, 'Asia/Taipei', '2026-02-28 08:00:00'),
    (42, '2026-03-01', 77.1, 170.0, 'Asia/Taipei', '2026-03-01 08:00:00'),
    (42, '2026-03-13', 76.6, 168.9, 'Asia/Taipei', '2026-03-13 08:00:00'),
    (42, '2026-03-19', 76.1, 167.8, 'Asia/Taipei', '2026-03-19 08:00:00'),
    (42, '2026-05-26', 74.8, 164.9, 'Asia/Taipei', '2026-05-26 08:00:00'),
    (42, '2026-05-27', 74.5, 164.2, 'Asia/Taipei', '2026-05-27 08:00:00'),
    (42, '2026-05-28', 74.2, 163.6, 'Asia/Taipei', '2026-05-28 08:00:00')
ON DUPLICATE KEY UPDATE
    weight_kg = VALUES(weight_kg),
    weight_lbs = VALUES(weight_lbs),
    timezone = VALUES(timezone),
    created_at = VALUES(created_at);

INSERT INTO weight_history
    (user_id, log_date, weight_kg, weight_lbs, timezone, photo_url, created_at, updated_at)
VALUES
    (42, '2025-06-01', 82.8, 182.5, 'Asia/Taipei', NULL, '2025-06-01 08:00:00', '2025-06-01 08:00:00'),
    (42, '2025-06-30', 82.1, 181.0, 'Asia/Taipei', NULL, '2025-06-30 08:00:00', '2025-06-30 08:00:00'),
    (42, '2025-07-09', 81.7, 180.1, 'Asia/Taipei', NULL, '2025-07-09 08:00:00', '2025-07-09 08:00:00'),
    (42, '2025-12-05', 79.8, 176.0, 'Asia/Taipei', NULL, '2025-12-05 08:00:00', '2025-12-05 08:00:00'),
    (42, '2026-01-01', 78.9, 173.9, 'Asia/Taipei', NULL, '2026-01-01 08:00:00', '2026-01-01 08:00:00'),
    (42, '2026-01-29', 78.2, 172.4, 'Asia/Taipei', NULL, '2026-01-29 08:00:00', '2026-01-29 08:00:00'),
    (42, '2026-01-30', 78.0, 172.0, 'Asia/Taipei', NULL, '2026-01-30 08:00:00', '2026-01-30 08:00:00'),
    (42, '2026-02-28', 77.4, 170.6, 'Asia/Taipei', NULL, '2026-02-28 08:00:00', '2026-02-28 08:00:00'),
    (42, '2026-03-01', 77.1, 170.0, 'Asia/Taipei', NULL, '2026-03-01 08:00:00', '2026-03-01 08:00:00'),
    (42, '2026-03-13', 76.6, 168.9, 'Asia/Taipei', NULL, '2026-03-13 08:00:00', '2026-03-13 08:00:00'),
    (42, '2026-03-19', 76.1, 167.8, 'Asia/Taipei', NULL, '2026-03-19 08:00:00', '2026-03-19 08:00:00'),
    (42, '2026-05-26', 74.8, 164.9, 'Asia/Taipei', NULL, '2026-05-26 08:00:00', '2026-05-26 08:00:00'),
    (42, '2026-05-27', 74.5, 164.2, 'Asia/Taipei', NULL, '2026-05-27 08:00:00', '2026-05-27 08:00:00'),
    (42, '2026-05-28', 74.2, 163.6, 'Asia/Taipei', NULL, '2026-05-28 08:00:00', '2026-05-28 08:00:00')
ON DUPLICATE KEY UPDATE
    weight_kg = VALUES(weight_kg),
    weight_lbs = VALUES(weight_lbs),
    timezone = VALUES(timezone),
    photo_url = VALUES(photo_url),
    created_at = VALUES(created_at),
    updated_at = VALUES(updated_at);

UPDATE user_profiles
SET goal_weight_kg = 68.0,
    goal_weight_lbs = 149.9,
    weight_kg = 74.2,
    weight_lbs = 163.6,
    unit_preference = 'KG',
    timezone = 'Asia/Taipei',
    updated_at = CURRENT_TIMESTAMP
WHERE user_id = 42;
