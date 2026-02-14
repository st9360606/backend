package com.calai.backend.foodlog;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.web.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UserRateLimiterTest {

    @Test
    void freeRateLimitExceededThrows() {
        // free=3/min, paid=20/min
        UserRateLimiter limiter = new UserRateLimiter(3, 20);

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
        assertTrue(ex.retryAfterSec() >= 0);
    }

    @Test
    void paidRateLimitExceededThrows() {
        // free=3/min, paid=5/min（方便測）
        UserRateLimiter limiter = new UserRateLimiter(3, 5);

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
        assertTrue(ex.retryAfterSec() >= 0);
    }
}
