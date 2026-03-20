package com.calai.backend.foodlog.service.limiter;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class UserRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private UserRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);

        limiter = new UserRateLimiter(
                6,              // freePerMinuteLimit
                20,             // paidPerMinuteLimit
                "bitecal-test", // redisPrefix
                redisTemplate
        );
    }

    @Test
    void checkOrThrow_should_pass_when_under_free_limit() {
        // currentCount=1, ttl=60
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any()
        )).thenReturn("1:60");

        assertThatCode(() ->
                limiter.checkOrThrow(
                        1L,
                        EntitlementService.Tier.NONE,
                        Instant.parse("2026-03-03T00:00:10Z")
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void checkOrThrow_should_pass_when_under_paid_limit() {
        // paid limit = 20，currentCount=8 還不會超限
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any()
        )).thenReturn("8:42");

        assertThatCode(() ->
                limiter.checkOrThrow(
                        1L,
                        EntitlementService.Tier.MONTHLY,
                        Instant.parse("2026-03-03T00:00:18Z")
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void checkOrThrow_should_throw_rate_limited_when_exceed_free_limit() {
        // free limit = 6，這裡 currentCount=7，應丟 RateLimitedException
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any()
        )).thenReturn("7:15");

        assertThatThrownBy(() ->
                limiter.checkOrThrow(
                        99L,
                        EntitlementService.Tier.TRIAL,
                        Instant.parse("2026-03-03T00:00:45Z")
                )
        )
                .isInstanceOf(RateLimitedException.class)
                .hasMessage("RATE_LIMITED");
    }

    @Test
    void checkOrThrow_should_throw_rate_limited_when_exceed_paid_limit() {
        // paid limit = 20，這裡 currentCount=21，應丟 RateLimitedException
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any()
        )).thenReturn("21:9");

        assertThatThrownBy(() ->
                limiter.checkOrThrow(
                        99L,
                        EntitlementService.Tier.YEARLY,
                        Instant.parse("2026-03-03T00:00:51Z")
                )
        )
                .isInstanceOf(RateLimitedException.class)
                .hasMessage("RATE_LIMITED");
    }

    @Test
    void checkOrThrow_should_throw_illegal_state_when_redis_execute_fails() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any()
        )).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() ->
                limiter.checkOrThrow(
                        1L,
                        EntitlementService.Tier.NONE,
                        Instant.parse("2026-03-03T00:00:10Z")
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RATE_LIMITER_REDIS_FAILED");
    }

    @Test
    void checkOrThrow_should_throw_illegal_state_when_redis_returns_blank() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any()
        )).thenReturn("");

        assertThatThrownBy(() ->
                limiter.checkOrThrow(
                        1L,
                        EntitlementService.Tier.NONE,
                        Instant.parse("2026-03-03T00:00:10Z")
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RATE_LIMITER_REDIS_FAILED");
    }

    @Test
    void checkOrThrow_should_throw_illegal_state_when_redis_returns_bad_format() {
        when(redisTemplate.execute(
                Mockito.<RedisScript<String>>any(),
                anyList(),
                any()
        )).thenReturn("bad-format");

        assertThatThrownBy(() ->
                limiter.checkOrThrow(
                        1L,
                        EntitlementService.Tier.NONE,
                        Instant.parse("2026-03-03T00:00:10Z")
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RATE_LIMITER_REDIS_FAILED");
    }

    @Test
    void checkOrThrow_should_ignore_when_userId_is_null() {
        assertThatCode(() ->
                limiter.checkOrThrow(
                        null,
                        EntitlementService.Tier.NONE,
                        Instant.parse("2026-03-03T00:00:10Z")
                )
        ).doesNotThrowAnyException();
    }
}
