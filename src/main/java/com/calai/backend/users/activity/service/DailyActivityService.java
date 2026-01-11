package com.calai.backend.users.activity.service;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.repo.UserDailyActivityRepository;
import com.calai.backend.weight.repo.WeightTimeseriesRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Service
public class DailyActivityService {

    private final UserDailyActivityRepository repo;
    private final WeightTimeseriesRepo weightSeries;

    /**
     * ✅ Retention：保留 8 天（以 Instant/UTC 時間線計算，8*24 小時）
     * delete where day_end_utc < now - 8 days
     */
    private static final Duration RETENTION = Duration.ofDays(8);

    /**
     * 估算步行消耗熱量：
     * kcal ≈ weightKg × steps × 0.0005
     */
    private static final double COEFF = 0.0005d;
    private static final String PKG_GOOGLE_FIT = "com.google.android.apps.fitness";
    private static final String PKG_SAMSUNG_HEALTH = "com.sec.android.app.shealth";

    private static final String ORIGIN_GOOGLE_FIT = "Google Fit";
    private static final String ORIGIN_SAMSUNG_HEALTH = "Samsung Health";
    private static final String ORIGIN_OTHER = "Other";

    public record UpsertReq(
            LocalDate localDate,
            String timezone,
            Long steps,
            Double activeKcal,
            UserDailyActivity.IngestSource ingestSource,
            String dataOriginPackage,
            String dataOriginName
    ) {}

    @Transactional
    public void upsert(Long userId, UpsertReq req) {
        ZoneId zone = parseZoneOr400(req.timezone());

        // ✅ DST safe：用「隔天 atStartOfDay」
        Instant start = req.localDate().atStartOfDay(zone).toInstant();
        Instant end = req.localDate().plusDays(1).atStartOfDay(zone).toInstant();

        UserDailyActivity.IngestSource ingest = resolveIngest(req.ingestSource());

        if (ingest == UserDailyActivity.IngestSource.HEALTH_CONNECT) {
            if (req.dataOriginPackage() == null || req.dataOriginPackage().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "dataOriginPackage is required for HEALTH_CONNECT"
                );
            }
        }

        UserDailyActivity e = repo.findByUserIdAndLocalDate(userId, req.localDate())
                .orElseGet(UserDailyActivity::new);

        e.setUserId(userId);
        e.setLocalDate(req.localDate());
        e.setTimezone(req.timezone());
        e.setDayStartUtc(start);
        e.setDayEndUtc(end);

        e.setSteps(req.steps());

        Double computed = computeEstimatedActiveKcalFromLatestWeight(userId, req.steps());

        Double activeKcalToStore;
        if (ingest == UserDailyActivity.IngestSource.HEALTH_CONNECT) {
            activeKcalToStore = (computed != null) ? computed : req.activeKcal();
        } else {
            activeKcalToStore = (req.activeKcal() != null) ? req.activeKcal() : computed;
        }

        e.setActiveKcal(activeKcalToStore);

        e.setIngestSource(ingest);
        e.setDataOriginPackage(req.dataOriginPackage());
        e.setDataOriginName(normalizeOriginName(req.dataOriginPackage()));

        repo.save(e);

        // ✅ Upsert 時順手清理該 user 的過期資料（小範圍）
        cleanupExpiredForUser(userId);
    }

    /**
     * 用「該 user 最新一筆 weight_timeseries.weight_kg」估算 kcal
     * - steps null/<=0：回 null（不寫）
     * - 找不到最新體重：回 null（不寫）
     * - 四捨五入到整數 kcal，但欄位型別仍用 Double 存入（例如 300.0）
     */
    private Double computeEstimatedActiveKcalFromLatestWeight(Long userId, Long steps) {
        if (steps == null || steps <= 0L) return null;

        var latest = weightSeries.findLatest(userId, PageRequest.of(0, 1));
        if (latest == null || latest.isEmpty()) return null;

        var kg = latest.getFirst().getWeightKg();
        if (kg == null) return null;

        double kcal = kg.doubleValue() * steps.doubleValue() * COEFF;
        return (double) Math.round(kcal);
    }

    @Transactional(readOnly = true)
    public List<UserDailyActivity> getRange(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from/to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be >= from");
        }
        return repo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, from, to);
    }

    //cleanupExpiredForUser(userId)：每次有使用者呼叫 upsert 成功寫入後
    //就會被觸發（同一個 transaction 內），只清那個 user 的過期資料。
    @Transactional
    public void cleanupExpiredForUser(Long userId) {
        Instant cutoff = Instant.now().minus(RETENTION);

        long t0 = System.nanoTime();
        int deleted = repo.deleteExpiredForUser(userId, cutoff);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // ✅ 建議：有刪到才 INFO，沒刪到用 DEBUG，避免洗版
        if (elapsedMs > 200) {
            log.warn("DailyActivity cleanupExpiredForUser SLOW: userId={}, cutoff={}, deletedRows={}, elapsedMs={}",
                    userId, cutoff, deleted, elapsedMs);
        } else if (deleted > 0) {
            log.info("DailyActivity cleanupExpiredForUser: userId={}, cutoff={}, deletedRows={}, elapsedMs={}",
                    userId, cutoff, deleted, elapsedMs);
        } else {
            log.debug("DailyActivity cleanupExpiredForUser: userId={}, cutoff={}, deletedRows=0, elapsedMs={}",
                    userId, cutoff, elapsedMs);
        }
    }

    //全域排程：每天清一次（UTC 03:10）
    //cleanupExpiredGlobal()：每天固定 UTC 03:10 被 Spring 排程觸發一次，清全表的過期資料。
    //兩者差別：觸發時機（即時 vs 每日）、範圍（單一 user vs 全部 user）、目的（減少單 user 長期累積 + 全域保底清理）。
    @Scheduled(cron = "0 10 3 * * *", zone = "UTC")
    @Transactional
    public int cleanupExpiredGlobal() {
        Instant cutoff = Instant.now().minus(RETENTION);

        long t0 = System.nanoTime();
        int deleted = repo.deleteAllExpired(cutoff);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // 全域每天一次，INFO OK
        if (elapsedMs > 500) { // 全域可放寬一點，例如 500ms
            log.warn("DailyActivity cleanupExpiredGlobal SLOW: cutoff={}, deletedRows={}, elapsedMs={}",
                    cutoff, deleted, elapsedMs);
        } else {
            log.info("DailyActivity cleanupExpiredGlobal: cutoff={}, deletedRows={}, elapsedMs={}",
                    cutoff, deleted, elapsedMs);
        }
        return deleted;
    }

    private static ZoneId parseZoneOr400(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (ZoneRulesException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid timezone: " + tz);
        }
    }

    /**
     * ✅ 你要的規則：
     * - Google Fit -> "Google Fit"
     * - Samsung Health -> "Samsung Health"
     * - 其他 -> "Other"
     * 注意：即便 client 有送其它名字（例如只送 "Fit"），仍會被規則化成 "Google Fit"
     * 這樣 DB 才一致、客服排查也穩。
     */
    private static String normalizeOriginName(String pkg) {
        if (PKG_GOOGLE_FIT.equals(pkg)) return ORIGIN_GOOGLE_FIT;
        if (PKG_SAMSUNG_HEALTH.equals(pkg)) return ORIGIN_SAMSUNG_HEALTH;
        return ORIGIN_OTHER;
    }

    private static UserDailyActivity.IngestSource resolveIngest(UserDailyActivity.IngestSource ingestSource) {
        return (ingestSource != null) ? ingestSource : UserDailyActivity.IngestSource.HEALTH_CONNECT;
    }
}
