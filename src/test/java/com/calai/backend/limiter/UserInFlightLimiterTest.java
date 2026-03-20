package com.calai.backend.foodlog.service.limiter;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
