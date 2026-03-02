package com.calai.backend.foodlog.service.limiter;

import com.calai.backend.foodlog.web.TooManyInFlightException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ✅ MVP 併發防爆：
 * - 每個 userId 對應一個 semaphore
 * - acquire 失敗直接 429，避免同一人灌爆磁碟 / CPU
 *
 * 修正：
 * 1. 防止多 release 造成 phantom permit
 * 2. 加入 idle eviction，避免 semMap 無限成長
 *
 * 注意：多機部署時仍是單機本地限制；要全域需要 Redis / DB。
 */
@Service
public class UserInFlightLimiter {

    private static final int CLEANUP_INTERVAL_MASK = 0xFF; // 每 256 次操作清一次

    private static final class Slot {
        private final Semaphore semaphore;
        private int acquired;
        private long lastTouchedEpochSec;

        Slot(int maxInFlight, long nowSec) {
            this.semaphore = new Semaphore(maxInFlight);
            this.acquired = 0;
            this.lastTouchedEpochSec = nowSec;
        }

        synchronized boolean tryAcquire(long nowSec) {
            boolean ok = semaphore.tryAcquire();
            if (ok) {
                acquired += 1;
                lastTouchedEpochSec = nowSec;
            }
            return ok;
        }

        synchronized void release(long nowSec) {
            if (acquired <= 0) {
                return; // 防呆：避免 phantom permit
            }
            acquired -= 1;
            lastTouchedEpochSec = nowSec;
            semaphore.release();
        }

        synchronized boolean isEvictable(long nowSec, long idleEvictSeconds) {
            return acquired == 0 && (nowSec - lastTouchedEpochSec) >= idleEvictSeconds;
        }
    }

    private final ConcurrentHashMap<Long, Slot> semMap = new ConcurrentHashMap<>();
    private final AtomicInteger opCounter = new AtomicInteger(0);

    private final int maxInFlight;
    private final int idleEvictSeconds;
    private final Clock clock;

    public UserInFlightLimiter(
            @Value("${app.guard.inflight.max:2}") int maxInFlight,
            @Value("${app.guard.inflight.idle-evict-seconds:3600}") int idleEvictSeconds,
            Clock clock
    ) {
        this.maxInFlight = Math.max(1, maxInFlight);
        this.idleEvictSeconds = Math.max(60, idleEvictSeconds);
        this.clock = clock;
    }

    public void acquireOrThrow(Long userId) {
        if (userId == null) return;

        long nowSec = clock.instant().getEpochSecond();
        Slot slot = semMap.computeIfAbsent(userId, k -> new Slot(maxInFlight, nowSec));
        boolean ok = slot.tryAcquire(nowSec);

        maybeCleanup(nowSec);

        if (!ok) {
            throw new TooManyInFlightException("TOO_MANY_IN_FLIGHT", 1, "RETRY_LATER");
        }
    }

    public void release(Long userId) {
        if (userId == null) return;

        Slot slot = semMap.get(userId);
        if (slot == null) return;

        long nowSec = clock.instant().getEpochSecond();
        slot.release(nowSec);
        maybeCleanup(nowSec);
    }

    private void maybeCleanup(long nowSec) {
        if ((opCounter.incrementAndGet() & CLEANUP_INTERVAL_MASK) != 0) {
            return;
        }

        for (Map.Entry<Long, Slot> entry : semMap.entrySet()) {
            Slot slot = entry.getValue();
            if (slot.isEvictable(nowSec, idleEvictSeconds)) {
                semMap.remove(entry.getKey(), slot);
            }
        }
    }
}
