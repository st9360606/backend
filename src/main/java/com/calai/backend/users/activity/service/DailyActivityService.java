package com.calai.backend.users.activity.service;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.repo.UserDailyActivityRepository;
import com.calai.backend.weight.repo.WeightTimeseriesRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;

@RequiredArgsConstructor
@Service
public class DailyActivityService {

    private final UserDailyActivityRepository repo;
    private final WeightTimeseriesRepo weightSeries;

    /**
     * 估算步行消耗熱量：
     * kcal ≈ weightKg × steps × 0.0005
     */
    private static final double COEFF = 0.0005d;

    public record UpsertReq(
            LocalDate localDate,
            String timezone,
            Long steps,                 // nullable
            Double activeKcal,          // nullable (payload, allow fallback)
            UserDailyActivity.IngestSource ingestSource,
            String dataOriginPackage,   // nullable, HC 建議必填
            String dataOriginName       // nullable
    ) {}

    @Transactional
    public void upsert(Long userId, UpsertReq req) {
        ZoneId zone = parseZoneOr400(req.timezone());

        // ✅ DST safe：用「隔天 atStartOfDay」
        Instant start = req.localDate().atStartOfDay(zone).toInstant();
        Instant end = req.localDate().plusDays(1).atStartOfDay(zone).toInstant();

        UserDailyActivity.IngestSource ingest = (req.ingestSource() != null)
                ? req.ingestSource()
                : UserDailyActivity.IngestSource.HEALTH_CONNECT;

        // ✅ 規格：HEALTH_CONNECT 時建議要求 data_origin_package（避免客服無法解釋）
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

        // ✅ PUT 全量覆蓋：payload null 就覆蓋成 null（不能 default 0）
        e.setSteps(req.steps());

        // ✅ 核心：active_kcal 一律由後端用「最新體重 + steps」計算（至少 HEALTH_CONNECT 要這樣）
        Double computed = computeEstimatedActiveKcalFromLatestWeight(userId, req.steps());

        // 規格：
        // - HEALTH_CONNECT：computed 有就用 computed；沒有就存 req.activeKcal（通常 client 送 null → 就是 null）
        // - 其他來源：如果 client 有送 activeKcal 就尊重；沒送才用 computed（可選）
        Double activeKcalToStore;
        if (ingest == UserDailyActivity.IngestSource.HEALTH_CONNECT) {
            activeKcalToStore = (computed != null) ? computed : req.activeKcal();
        } else {
            activeKcalToStore = (req.activeKcal() != null) ? req.activeKcal() : computed;
        }

        e.setActiveKcal(activeKcalToStore);

        e.setIngestSource(ingest);
        e.setDataOriginPackage(req.dataOriginPackage());
        e.setDataOriginName(req.dataOriginName());

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

        var kg = latest.get(0).getWeightKg();
        if (kg == null) return null;

        double kcal = kg.doubleValue() * steps.doubleValue() * COEFF;
        long rounded = Math.round(kcal); // 與 Android roundToInt 對齊
        return (double) rounded;
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

    @Transactional
    public int cleanupExpiredForUser(Long userId) {
        Instant cutoff = Instant.now().minusSeconds(7L * 24 * 3600);
        return repo.deleteExpiredForUser(userId, cutoff);
    }

    // ✅ 全域排程：每天清一次（UTC 03:10）
    @Scheduled(cron = "0 10 3 * * *", zone = "UTC")
    @Transactional
    public int cleanupExpiredGlobal() {
        Instant cutoff = Instant.now().minusSeconds(7L * 24 * 3600);
        return repo.deleteAllExpired(cutoff);
    }

    private static ZoneId parseZoneOr400(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (ZoneRulesException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid timezone: " + tz);
        }
    }
}
