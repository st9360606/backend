package com.calai.backend.foodlog.service.limiter;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import com.calai.backend.foodlog.web.error.TooManyInFlightException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserInFlightLimiterTest {

    private StringRedisTemplate redisTemplate;
    private Clock clock;
    private UserInFlightLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        clock = Clock.fixed(Instant.parse("2026-03-03T00:00:00Z"), ZoneOffset.UTC);

        limiter = new UserInFlightLimiter(
                2,                      // maxInFlight
                Duration.ofMinutes(5),  // lease ttl
                "bitecal-test",         // redis prefix
                clock,
                redisTemplate
        );
    }

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

    @Test
    void acquireOrThrow_should_return_lease_when_acquire_success() {
        // 回傳格式："{okFlag}:{currentCount}:{retryAfterSec}"
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any(), any(), any(), any(), any()
        )).thenReturn("1:1:0");

        UserInFlightLimiter.Lease lease = limiter.acquireOrThrow(123L);

        assertThat(lease).isNotNull();
        assertThat(lease.userId()).isEqualTo(123L);
        assertThat(lease.token()).isNotBlank();
    }

    @Test
    void acquireOrThrow_should_throw_too_many_in_flight_when_rejected() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any(), any(), any(), any(), any()
        )).thenReturn("0:2:7");

        assertThatThrownBy(() -> limiter.acquireOrThrow(123L))
                .isInstanceOf(TooManyInFlightException.class)
                .hasMessage("TOO_MANY_IN_FLIGHT");
    }

    @Test
    void acquireOrThrow_should_throw_illegal_state_when_redis_execute_fails() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any(), any(), any(), any(), any()
        )).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> limiter.acquireOrThrow(123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INFLIGHT_LIMITER_REDIS_FAILED");
    }

    @Test
    void acquireOrThrow_should_throw_illegal_state_when_redis_returns_blank() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any(), any(), any(), any(), any()
        )).thenReturn("");

        assertThatThrownBy(() -> limiter.acquireOrThrow(123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INFLIGHT_LIMITER_REDIS_FAILED");
    }

    @Test
    void acquireOrThrow_should_throw_illegal_state_when_redis_returns_bad_format() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any(), any(), any(), any(), any()
        )).thenReturn("bad");

        assertThatThrownBy(() -> limiter.acquireOrThrow(123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INFLIGHT_LIMITER_REDIS_FAILED");
    }

    @Test
    void acquireOrThrow_should_return_null_when_userId_is_null() {
        UserInFlightLimiter.Lease lease = limiter.acquireOrThrow(null);
        assertThat(lease).isNull();
    }

    @Test
    void release_should_not_throw_when_release_success() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any(), any(), any()
        )).thenReturn("1:0");

        UserInFlightLimiter.Lease lease =
                new UserInFlightLimiter.Lease(123L, "token-123");

        assertThatCode(() -> limiter.release(lease))
                .doesNotThrowAnyException();
    }

    @Test
    void release_should_not_throw_when_redis_execute_fails() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any(), any(), any()
        )).thenThrow(new RuntimeException("redis release fail"));

        UserInFlightLimiter.Lease lease =
                new UserInFlightLimiter.Lease(123L, "token-123");

        assertThatCode(() -> limiter.release(lease))
                .doesNotThrowAnyException();
    }

    @Test
    void release_should_ignore_invalid_lease() {
        assertThatCode(() -> limiter.release(null))
                .doesNotThrowAnyException();

        assertThatCode(() -> limiter.release(new UserInFlightLimiter.Lease(null, "abc")))
                .doesNotThrowAnyException();

        assertThatCode(() -> limiter.release(new UserInFlightLimiter.Lease(1L, null)))
                .doesNotThrowAnyException();

        assertThatCode(() -> limiter.release(new UserInFlightLimiter.Lease(1L, "   ")))
                .doesNotThrowAnyException();
    }
}
