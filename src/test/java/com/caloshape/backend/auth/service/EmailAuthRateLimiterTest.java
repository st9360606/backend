package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.config.EmailAuthRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailAuthRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-07-14T04:00:00Z");

    @Test
    void startChecksEmailIpAndDeviceWithoutStoringRawIdentifiers() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(
                any(RedisScript.class),
                anyList(),
                any()
        )).thenReturn("1:900");
        EmailAuthRateLimiter limiter = new EmailAuthRateLimiter(redis, properties());

        assertThatCode(() -> limiter.checkStart(
                "person@example.com",
                "203.0.113.10",
                "device-123",
                NOW
        )).doesNotThrowAnyException();
    }

    @Test
    void exceedingDimensionReturnsRetryAfter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(
                any(RedisScript.class),
                anyList(),
                any()
        )).thenReturn("4:600");
        EmailAuthRateLimiter limiter = new EmailAuthRateLimiter(redis, properties());

        assertThatThrownBy(() -> limiter.checkStart(
                "person@example.com",
                "203.0.113.10",
                "device-123",
                NOW
        )).isInstanceOfSatisfying(
                EmailAuthRateLimitException.class,
                ex -> org.assertj.core.api.Assertions.assertThat(ex.getRetryAfterSec()).isEqualTo(600)
        );
    }

    @Test
    void redisFailureFailsClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(
                any(RedisScript.class),
                anyList(),
                any()
        )).thenThrow(new IllegalStateException("offline"));
        EmailAuthRateLimiter limiter = new EmailAuthRateLimiter(redis, properties());

        assertThatThrownBy(() -> limiter.checkVerify(
                "person@example.com",
                "203.0.113.10",
                "device-123",
                NOW
        )).isInstanceOf(EmailAuthUnavailableException.class);
    }

    private static EmailAuthRateLimitProperties properties() {
        EmailAuthRateLimitProperties properties = new EmailAuthRateLimitProperties();
        properties.setRedisPrefix("caloshape-test");
        properties.setWindow(Duration.ofMinutes(15));
        properties.setStart(new EmailAuthRateLimitProperties.Limits(3, 20, 5));
        properties.setVerify(new EmailAuthRateLimitProperties.Limits(10, 60, 20));
        return properties;
    }
}
