package com.calai.backend.foodlog.barcode.openfoodfacts.rate;

import com.calai.backend.foodlog.barcode.openfoodfacts.error.OffHttpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-node fixed window limiter (per minute).
 * Production (multi-node) should use Redis.
 */
@Component
public class OffGlobalRateLimiter {

    private final int limitPerMinute;

    private final AtomicLong windowMinute = new AtomicLong(-1);
    private final AtomicInteger counter = new AtomicInteger(0);

    public OffGlobalRateLimiter(@Value("${app.openfoodfacts.global-read-limit-per-min:90}") int limitPerMinute) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
    }

    public void acquireOrThrow(Instant now) {
        long m = now.getEpochSecond() / 60;

        long cur = windowMinute.get();
        if (cur != m) {
            // best-effort reset
            windowMinute.set(m);
            counter.set(0);
        }

        int n = counter.incrementAndGet();
        if (n > limitPerMinute) {
            throw new OffHttpException(429, "OFF_GLOBAL_RATE_LIMIT", "global limiter exceeded");
        }
    }
}

