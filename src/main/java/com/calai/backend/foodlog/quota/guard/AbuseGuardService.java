package com.calai.backend.foodlog.quota.guard;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class AbuseGuardService {

    private final AbuseGuardProperties props;
    private final AbuseGuardTxWriter txWriter;
    private final ConcurrentHashMap<Long, Window> userWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceWindow> deviceWindows = new ConcurrentHashMap<>();

    public void onOperationAttempt(Long userId, String deviceId, boolean cacheHit, Instant nowUtc, ZoneId userTz) {
        if (!props.isEnabled()) return;

        long bucket = bucketStart(nowUtc);

        Window w = userWindows.compute(userId, (k, old) ->
                (old == null || old.bucket != bucket) ? new Window(bucket, nowUtc.getEpochSecond()) : old.touch(nowUtc)
        );
        w.ops.incrementAndGet();
        if (cacheHit) w.cacheHits.incrementAndGet();

        // ✅ 抽共用 device-switch 邏輯
        checkDeviceSwitch(userId, deviceId, bucket, nowUtc, userTz);

        int ops = w.ops.get();
        int hits = w.cacheHits.get();
        double hitRate = ops == 0 ? 1.0 : ((double) hits / (double) ops);

        if (ops > props.getHourOpsThreshold() && hitRate < props.getMinCacheHitRate()) {
            txWriter.triggerAbuseAndThrow(userId, nowUtc, userTz);
        }
    }

    /**
     * ✅ BARCODE 專用：只做 device-switch，不參與 user cache hit rate 統計
     */
    public void onBarcodeAttempt(Long userId, String deviceId, Instant nowUtc, ZoneId userTz) {
        if (!props.isEnabled()) return;

        long bucket = bucketStart(nowUtc);
        checkDeviceSwitch(userId, deviceId, bucket, nowUtc, userTz);
    }

    private void checkDeviceSwitch(Long userId, String deviceId, long bucket, Instant nowUtc, ZoneId userTz) {
        if (deviceId == null || deviceId.isBlank()) return;

        DeviceWindow dw = deviceWindows.compute(deviceId, (k, old) ->
                (old == null || old.bucket != bucket) ? new DeviceWindow(bucket, nowUtc.getEpochSecond()) : old.touch(nowUtc)
        );
        dw.ops.incrementAndGet();
        dw.userIds.add(userId);

        if (dw.userIds.size() >= props.getDeviceSwitchThreshold()) {
            txWriter.triggerAbuseAndThrow(userId, nowUtc, userTz);
        }
    }

    private long bucketStart(Instant nowUtc) {
        long sizeSec = Math.max(60L, props.getWindow().getSeconds());
        long nowSec = nowUtc.getEpochSecond();
        return (nowSec / sizeSec) * sizeSec;
    }

    @Scheduled(fixedDelay = 300_000L)
    void cleanupExpired() {
        if (!props.isEnabled()) return;

        long now = Instant.now().getEpochSecond();
        long ttl = Math.max(60L, props.getKeyTtl().getSeconds());

        userWindows.entrySet().removeIf(e -> (now - e.getValue().lastTouchedEpochSec) > ttl);
        deviceWindows.entrySet().removeIf(e -> (now - e.getValue().lastTouchedEpochSec) > ttl);
    }

    private static final class Window {
        final long bucket;
        final AtomicInteger ops = new AtomicInteger(0);
        final AtomicInteger cacheHits = new AtomicInteger(0);
        volatile long lastTouchedEpochSec;

        Window(long bucket, long touched) {
            this.bucket = bucket;
            this.lastTouchedEpochSec = touched;
        }

        Window touch(Instant nowUtc) {
            this.lastTouchedEpochSec = nowUtc.getEpochSecond();
            return this;
        }
    }

    private static final class DeviceWindow {
        final long bucket;
        final AtomicInteger ops = new AtomicInteger(0);
        final Set<Long> userIds = ConcurrentHashMap.newKeySet();
        volatile long lastTouchedEpochSec;

        DeviceWindow(long bucket, long touched) {
            this.bucket = bucket;
            this.lastTouchedEpochSec = touched;
        }

        DeviceWindow touch(Instant nowUtc) {
            this.lastTouchedEpochSec = nowUtc.getEpochSecond();
            return this;
        }
    }
}
