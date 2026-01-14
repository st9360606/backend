package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.web.TooManyInFlightException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * ✅ MVP 併發防爆：
 * - 每個 userId 對應一個 semaphore
 * - acquire 失敗直接 429，避免你磁碟/CPU 被同一人灌爆
 *
 * 注意：多機部署時是「每台機器各自限制」；要全域需要 Redis/DB。
 */
@Service
public class UserInFlightLimiter {

    private final ConcurrentHashMap<Long, Semaphore> semMap = new ConcurrentHashMap<>();

    private final int maxInFlight;

    public UserInFlightLimiter(@Value("${app.guard.inflight.max:2}") int maxInFlight) {
        this.maxInFlight = Math.max(1, maxInFlight);
    }

    public void acquireOrThrow(Long userId) {
        if (userId == null) return;
        Semaphore sem = semMap.computeIfAbsent(userId, k -> new Semaphore(maxInFlight));
        boolean ok = sem.tryAcquire();
        if (!ok) {
            // 1 秒後再試（App 端可做退避）
            throw new TooManyInFlightException("TOO_MANY_IN_FLIGHT", 1, "RETRY_LATER");
        }
    }

    public void release(Long userId) {
        if (userId == null) return;
        Semaphore sem = semMap.get(userId);
        if (sem != null) sem.release();
    }
}
