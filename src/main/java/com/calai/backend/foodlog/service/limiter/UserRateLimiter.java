package com.calai.backend.foodlog.service.limiter;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.web.RateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ✅ MVP 速率限制（固定視窗 60 秒）
 * - 每個 userId 一個 window
 * - Free / Trial / None：freePerMinuteLimit（預設 6）
 * - Paid（MONTHLY / YEARLY）：paidPerMinuteLimit（預設 20）
 *
 * 修正：
 * 1. reset + increment 放進同一個 synchronized 區塊，避免 minute rollover 併發 under-count
 * 2. 加入 idle eviction，避免 map 無限成長
 *
 * 注意：多機部署時仍是單機本地限制；全域限制請改 Redis / Bucket4j。
 */
@Service
public class UserRateLimiter {

    private static final long WINDOW_SIZE_SEC = 60L;
    private static final int CLEANUP_INTERVAL_MASK = 0xFF; // 每 256 次操作清一次

    private static final class Window {
        long windowStartEpochSec;
        int count;
        volatile long lastSeenEpochSec;

        Window(long windowStartEpochSec, long lastSeenEpochSec) {
            this.windowStartEpochSec = windowStartEpochSec;
            this.lastSeenEpochSec = lastSeenEpochSec;
        }

        int increment(long expectedWindowStart, long nowSec) {
            synchronized (this) {
                if (this.windowStartEpochSec != expectedWindowStart) {
                    this.windowStartEpochSec = expectedWindowStart;
                    this.count = 0;
                }
                this.lastSeenEpochSec = nowSec;
                this.count += 1;
                return this.count;
            }
        }

        boolean isIdleTooLong(long nowSec, long idleEvictSeconds) {
            return (nowSec - lastSeenEpochSec) >= idleEvictSeconds;
        }
    }

    private final ConcurrentHashMap<Long, Window> map = new ConcurrentHashMap<>();
    private final AtomicInteger opCounter = new AtomicInteger(0);

    private final int freePerMinuteLimit;
    private final int paidPerMinuteLimit;
    private final int idleEvictSeconds;

    public UserRateLimiter(
            @Value("${app.guard.rate.free-per-minute:6}") int freePerMinuteLimit,
            @Value("${app.guard.rate.paid-per-minute:20}") int paidPerMinuteLimit,
            @Value("${app.guard.rate.idle-evict-seconds:3600}") int idleEvictSeconds
    ) {
        this.freePerMinuteLimit = Math.max(1, freePerMinuteLimit);
        this.paidPerMinuteLimit = Math.max(1, paidPerMinuteLimit);
        this.idleEvictSeconds = Math.max(60, idleEvictSeconds);
    }

    public void checkOrThrow(Long userId, EntitlementService.Tier tier, Instant nowUtc) {
        if (userId == null) return;

        int perMinuteLimit = isPaidTier(tier) ? paidPerMinuteLimit : freePerMinuteLimit;
        long nowSec = nowUtc.getEpochSecond();
        long windowStart = (nowSec / WINDOW_SIZE_SEC) * WINDOW_SIZE_SEC;

        Window w = map.computeIfAbsent(userId, k -> new Window(windowStart, nowSec));
        int currentCount = w.increment(windowStart, nowSec);

        maybeCleanup(nowSec);

        if (currentCount > perMinuteLimit) {
            int retryAfter = (int) Math.max(0, (windowStart + WINDOW_SIZE_SEC) - nowSec);
            throw new RateLimitedException("RATE_LIMITED", retryAfter, "RETRY_LATER");
        }
    }

    private void maybeCleanup(long nowSec) {
        if ((opCounter.incrementAndGet() & CLEANUP_INTERVAL_MASK) != 0) {
            return;
        }

        for (Map.Entry<Long, Window> entry : map.entrySet()) {
            Window w = entry.getValue();
            if (w.isIdleTooLong(nowSec, idleEvictSeconds)) {
                map.remove(entry.getKey(), w);
            }
        }
    }

    private static boolean isPaidTier(EntitlementService.Tier tier) {
        if (tier == null) return false;
        return tier == EntitlementService.Tier.MONTHLY || tier == EntitlementService.Tier.YEARLY;
    }
}
