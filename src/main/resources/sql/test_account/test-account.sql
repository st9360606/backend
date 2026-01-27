-- TRIAL 3 天：
INSERT INTO user_entitlements(id, user_id, entitlement_type, status,
                              valid_from_utc, valid_to_utc,
                              purchase_token_hash, last_verified_at_utc,
                              created_at_utc, updated_at_utc)
VALUES (UUID(), 1, 'TRIAL', 'ACTIVE',
        UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 3 DAY),
        REPEAT('a', 64), UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));


-- MONTHLY 30 天：
INSERT INTO user_entitlements(id, user_id, entitlement_type, status,
                              valid_from_utc, valid_to_utc,
                              purchase_token_hash, last_verified_at_utc,
                              created_at_utc, updated_at_utc)
VALUES (UUID(), 1, 'MONTHLY', 'ACTIVE',
        UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),
        REPEAT('a', 64), UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 驗證 entitlement 有效
SELECT *
FROM user_entitlements
WHERE user_id = 1
  AND status = 'ACTIVE'
  AND valid_from_utc <= UTC_TIMESTAMP(6)
  AND valid_to_utc > UTC_TIMESTAMP(6)
ORDER BY valid_to_utc DESC;


UPDATE user_entitlements
SET status='EXPIRED',
    valid_to_utc = UTC_TIMESTAMP(6),
    updated_at_utc = UTC_TIMESTAMP(6)
WHERE user_id = 1 AND status='ACTIVE';


UPDATE usage_counters
SET used_count = 200
WHERE user_id=1 AND local_date='2026-01-27';
