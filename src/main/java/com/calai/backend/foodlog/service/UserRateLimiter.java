package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.web.RateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ✅ MVP 速率限制（固定視窗 60 秒）
 * - 每個 userId 一個 window
 * - 超過 perMinuteLimit → 429
 *
 * 多機要全域：後續替換成 Redis/Bucket4j 都很容易（把介面保留）。
 */
@Service
public class UserRateLimiter {

    private static final class Window {
        volatile long windowStartEpochSec;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long start) { this.windowStartEpochSec = start; }
    }

    private final ConcurrentHashMap<Long, Window> map = new ConcurrentHashMap<>();
    private final int perMinuteLimit;

    public UserRateLimiter(@Value("${app.guard.rate.per-minute:20}") int perMinuteLimit) {
        this.perMinuteLimit = Math.max(1, perMinuteLimit);
    }

    public void checkOrThrow(Long userId, Instant nowUtc) {
        if (userId == null) return;

        long nowSec = nowUtc.getEpochSecond();
        long start = (nowSec / 60) * 60;

        Window w = map.computeIfAbsent(userId, k -> new Window(start));

        // 進入新分鐘：重置
        if (w.windowStartEpochSec != start) {
            synchronized (w) {
                if (w.windowStartEpochSec != start) {
                    w.windowStartEpochSec = start;
                    w.count.set(0);
                }
            }
        }

        int n = w.count.incrementAndGet();
        if (n > perMinuteLimit) {
            int retryAfter = (int) Math.max(0, (start + 60) - nowSec);
            throw new RateLimitedException("RATE_LIMITED", retryAfter, "RETRY_LATER");
        }
    }
}
