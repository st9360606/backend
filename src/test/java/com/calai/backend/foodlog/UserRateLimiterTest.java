package com.calai.backend.foodlog;

import com.calai.backend.foodlog.service.UserRateLimiter;
import com.calai.backend.foodlog.web.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UserRateLimiterTest {

    @Test
    void rateLimitExceededThrows() {
        UserRateLimiter limiter = new UserRateLimiter(3);

        Long uid = 1L;
        Instant now = Instant.parse("2026-01-14T00:00:10Z");

        limiter.checkOrThrow(uid, now);
        limiter.checkOrThrow(uid, now);
        limiter.checkOrThrow(uid, now);

        RateLimitedException ex = assertThrows(RateLimitedException.class, () -> limiter.checkOrThrow(uid, now));
        assertTrue(ex.retryAfterSec() >= 0);
    }
}
