package com.calai.backend.users.activity.service;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.repo.UserDailyActivityRepository;
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

@Service
public class DailyActivityService {

    private final UserDailyActivityRepository repo;

    public DailyActivityService(UserDailyActivityRepository repo) {
        this.repo = repo;
    }

    public record UpsertReq(
            LocalDate localDate,
            String timezone,
            Long steps,                 // nullable
            Double activeKcal,          // nullable
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataOriginPackage is required for HEALTH_CONNECT");
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
        e.setActiveKcal(req.activeKcal());

        e.setIngestSource(ingest);
        e.setDataOriginPackage(req.dataOriginPackage());
        e.setDataOriginName(req.dataOriginName());

        repo.save(e);

        // ✅ Upsert 時順手清理該 user 的過期資料（小範圍）
        cleanupExpiredForUser(userId);
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
