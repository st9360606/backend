package com.calai.backend.foodlog;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserRateLimiterTest {

    @Test
    void freeRateLimitExceededThrows() {
        // 1. mock RedisTemplate
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        // 2. 模擬 Redis script 回傳：
        // 第 1 次 -> "1:50"
        // 第 2 次 -> "2:50"
        // 第 3 次 -> "3:50"
        // 第 4 次 -> "4:50"（超限）
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString()
        )).thenReturn("1:50", "2:50", "3:50", "4:50");

        // free=3/min, paid=20/min
        UserRateLimiter limiter = new UserRateLimiter(3, 20, "bitecal", redisTemplate);

        Long uid = 1L;
        Instant now = Instant.parse("2026-01-14T00:00:10Z");

        // Free/Trial tier：允許 3 次
        limiter.checkOrThrow(uid, EntitlementService.Tier.TRIAL, now);
        limiter.checkOrThrow(uid, EntitlementService.Tier.TRIAL, now);
        limiter.checkOrThrow(uid, EntitlementService.Tier.TRIAL, now);

        // 第 4 次應該被擋
        RateLimitedException ex = assertThrows(
                RateLimitedException.class,
                () -> limiter.checkOrThrow(uid, EntitlementService.Tier.TRIAL, now)
        );

        assertEquals("RATE_LIMITED", ex.getMessage());
        assertTrue(ex.retryAfterSec() >= 1);
    }

    @Test
    void paidRateLimitExceededThrows() {
        // 1. mock RedisTemplate
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        // 2. 模擬 Redis script 回傳
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString()
        )).thenReturn("1:50", "2:50", "3:50", "4:50", "5:50", "6:50");

        // free=3/min, paid=5/min
        UserRateLimiter limiter = new UserRateLimiter(3, 5, "bitecal", redisTemplate);

        Long uid = 2L;
        Instant now = Instant.parse("2026-01-14T00:00:10Z");

        // Paid tier：允許 5 次
        limiter.checkOrThrow(uid, EntitlementService.Tier.MONTHLY, now);
        limiter.checkOrThrow(uid, EntitlementService.Tier.MONTHLY, now);
        limiter.checkOrThrow(uid, EntitlementService.Tier.MONTHLY, now);
        limiter.checkOrThrow(uid, EntitlementService.Tier.MONTHLY, now);
        limiter.checkOrThrow(uid, EntitlementService.Tier.MONTHLY, now);

        // 第 6 次應該被擋
        RateLimitedException ex = assertThrows(
                RateLimitedException.class,
                () -> limiter.checkOrThrow(uid, EntitlementService.Tier.MONTHLY, now)
        );

        assertEquals("RATE_LIMITED", ex.getMessage());
        assertTrue(ex.retryAfterSec() >= 1);
    }
}
