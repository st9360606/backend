package com.calai.backend.foodlog.service.limiter;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.web.RateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;

/**
 * ✅ Redis 多機全域速率限制（固定視窗 60 秒）
 * - 每個 userId + windowStart 一個 Redis key
 * - Free / Trial / None：freePerMinuteLimit（預設 6）
 * - Paid（MONTHLY / YEARLY）：paidPerMinuteLimit（預設 20）
 *
 * 特性：
 * 1. 多機共用同一份限流狀態
 * 2. key 會在當前 minute window 結束時自動過期
 * 3. retryAfter 直接取 Redis TTL
 */
@Slf4j
@Service
public class UserRateLimiter {

    private static final long WINDOW_SIZE_SEC = 60L;

    /**
     * 回傳格式："{currentCount}:{ttlSec}"
     *
     * ARGV[1] = expireSeconds
     */
    private static final RedisScript<String> RATE_CHECK_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local current = redis.call('INCR', KEYS[1])
                    if current == 1 then
                      redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
                    end
                    local ttl = redis.call('TTL', KEYS[1])
                    return tostring(current) .. ':' .. tostring(ttl)
                    """,
                    String.class
            );

    private record RedisResult(long currentCount, long ttlSec) {}

    private final StringRedisTemplate redisTemplate;
    private final int freePerMinuteLimit;
    private final int paidPerMinuteLimit;
    private final String redisKeyPrefix;

    public UserRateLimiter(
            @Value("${app.guard.rate.free-per-minute:6}") int freePerMinuteLimit,
            @Value("${app.guard.rate.paid-per-minute:20}") int paidPerMinuteLimit,
            @Value("${app.guard.rate.redis-prefix:bitecal}") String redisPrefix,
            StringRedisTemplate redisTemplate
    ) {
        this.freePerMinuteLimit = Math.max(1, freePerMinuteLimit);
        this.paidPerMinuteLimit = Math.max(1, paidPerMinuteLimit);
        this.redisKeyPrefix = normalizePrefix(redisPrefix) + ":guard:rate";
        this.redisTemplate = redisTemplate;
    }

    public void checkOrThrow(Long userId, EntitlementService.Tier tier, Instant nowUtc) {
        if (userId == null) return;

        int perMinuteLimit = isPaidTier(tier) ? paidPerMinuteLimit : freePerMinuteLimit;

        long nowSec = nowUtc.getEpochSecond();
        long windowStart = (nowSec / WINDOW_SIZE_SEC) * WINDOW_SIZE_SEC;
        long expireSeconds = Math.max(1L, (windowStart + WINDOW_SIZE_SEC) - nowSec);

        String key = rateKey(userId, windowStart);

        String raw;
        try {
            raw = redisTemplate.execute(
                    RATE_CHECK_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(expireSeconds)
            );
        } catch (Exception ex) {
            log.error("rate_limiter_redis_error userId={} tier={} key={} message={}",
                    userId, tier, key, ex.getMessage(), ex);
            throw new IllegalStateException("RATE_LIMITER_REDIS_FAILED", ex);
        }

        RedisResult result;
        try {
            result = parseRedisResult(raw, "RATE_LIMITER_REDIS_FAILED");
        } catch (IllegalStateException ex) {
            log.error("rate_limiter_bad_redis_result userId={} tier={} key={} raw={}",
                    userId, tier, key, raw, ex);
            throw ex;
        }

        if (result.currentCount > perMinuteLimit) {
            int retryAfter = (int) Math.max(1L, result.ttlSec > 0 ? result.ttlSec : expireSeconds);

            log.warn("rate_limited userId={} tier={} currentCount={} limit={} retryAfterSec={} windowStart={}",
                    userId, tier, result.currentCount, perMinuteLimit, retryAfter, windowStart);

            throw new RateLimitedException("RATE_LIMITED", retryAfter, "RETRY_LATER");
        }

        if (log.isDebugEnabled()) {
            log.debug("rate_check_pass userId={} tier={} currentCount={} limit={} ttlSec={} windowStart={}",
                    userId, tier, result.currentCount, perMinuteLimit, result.ttlSec, windowStart);
        }
    }

    private String rateKey(Long userId, long windowStart) {
        return redisKeyPrefix + ":user:" + userId + ":window:" + windowStart;
    }

    private static RedisResult parseRedisResult(String raw, String errorCode) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(errorCode);
        }

        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException(errorCode);
        }

        try {
            long currentCount = Long.parseLong(parts[0]);
            long ttlSec = Long.parseLong(parts[1]);
            return new RedisResult(currentCount, ttlSec);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(errorCode, ex);
        }
    }

    private static String normalizePrefix(String raw) {
        String s = (raw == null || raw.isBlank()) ? "bitecal" : raw.trim();
        while (s.endsWith(":")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean isPaidTier(EntitlementService.Tier tier) {
        if (tier == null) return false;
        return tier == EntitlementService.Tier.MONTHLY || tier == EntitlementService.Tier.YEARLY;
    }
}
