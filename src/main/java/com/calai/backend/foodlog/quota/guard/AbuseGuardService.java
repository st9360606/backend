package com.calai.backend.foodlog.quota.guard;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class AbuseGuardService {

    private final UserAiQuotaStateRepository stateRepo;
    private final AbuseGuardProperties props;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ConcurrentHashMap<Long, Window> userWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceWindow> deviceWindows = new ConcurrentHashMap<>();

    /**
     * @param cacheHit 這次是否命中「同 user + sha256 且有效 effective」
     * @param userTz   使用者時區（用於建立 quota state key）
     */
    public void onOperationAttempt(Long userId, String deviceId, boolean cacheHit, Instant nowUtc, ZoneId userTz) {
        if (!props.isEnabled()) return;

        long bucket = bucketStart(nowUtc);

        // Rule A：window 內 op > X 且 cacheHitRate < min
        Window w = userWindows.compute(userId, (k, old) ->
                (old == null || old.bucket != bucket) ? new Window(bucket, nowUtc.getEpochSecond()) : old.touch(nowUtc)
        );
        w.ops.incrementAndGet();
        if (cacheHit) w.cacheHits.incrementAndGet();

        // Rule B：同 device 切多帳號
        if (deviceId != null && !deviceId.isBlank()) {
            DeviceWindow dw = deviceWindows.compute(deviceId, (k, old) ->
                    (old == null || old.bucket != bucket) ? new DeviceWindow(bucket, nowUtc.getEpochSecond()) : old.touch(nowUtc)
            );
            dw.ops.incrementAndGet();
            dw.userIds.add(userId);

            if (dw.userIds.size() >= props.getDeviceSwitchThreshold()) {
                triggerAbuse(userId, nowUtc, userTz);
            }
        }

        int ops = w.ops.get();
        int hits = w.cacheHits.get();
        double hitRate = ops == 0 ? 1.0 : ((double) hits / (double) ops);

        if (ops > props.getHourOpsThreshold() && hitRate < props.getMinCacheHitRate()) {
            triggerAbuse(userId, nowUtc, userTz);
        }
    }

    /**
     * 注意：通常會在外層 @Transactional（createPhoto/createAlbum/createLabel）之內被呼叫。
     * 這裡仍保留 @Transactional 以支援未來獨立呼叫場景。
     */
    @Transactional
    void triggerAbuse(Long userId, Instant nowUtc, ZoneId userTz) {

        UserAiQuotaStateEntity s = stateRepo.findForUpdate(userId).orElseGet(() -> {
            UserAiQuotaStateEntity n = new UserAiQuotaStateEntity();
            n.setUserId(userId);
            n.setDailyKey(dayKey(nowUtc, userTz));
            n.setMonthlyKey(monthKey(nowUtc, userTz));
            n.setDailyCount(0);
            n.setMonthlyCount(0);
            n.setCooldownStrikes(0);
            n.setNextAllowedAtUtc(null);
            n.setForceLowUntilUtc(null);
            n.setCooldownReason(null);
            return n;
        });

        Instant nextCooldown = nowUtc.plus(props.getCooldown());
        Instant forceLowUntil = nowUtc.plus(props.getForceLow());

        // ✅ cooldown 與 forceLow 分離
        s.setNextAllowedAtUtc(nextCooldown);
        s.setForceLowUntilUtc(forceLowUntil);
        s.setCooldownReason(CooldownReason.ABUSE.name());
        s.setCooldownStrikes(Math.max(3, s.getCooldownStrikes())); // 直接視為 level=3
        stateRepo.save(s);

        throw new CooldownActiveException(
                "COOLDOWN_ACTIVE",
                nextCooldown,
                (int) props.getCooldown().getSeconds(),
                3,
                CooldownReason.ABUSE
        );
    }

    private long bucketStart(Instant nowUtc) {
        long sizeSec = Math.max(60L, props.getWindow().getSeconds());
        long nowSec = nowUtc.getEpochSecond();
        return (nowSec / sizeSec) * sizeSec;
    }

    private static String dayKey(Instant nowUtc, ZoneId tz) {
        return DAY.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static String monthKey(Instant nowUtc, ZoneId tz) {
        return MON.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    /** 每 5 分鐘清掉超過 keyTtl 未 touched 的 entry，避免 map 無限長大 */
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
