package com.calai.backend.users.activity.service;



import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.repo.UserDailyActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class DailyActivityService {

    private final UserDailyActivityRepository repo;

    public DailyActivityService(UserDailyActivityRepository repo) {
        this.repo = repo;
    }

    public record UpsertReq(LocalDate localDate, String timezone, Long steps, Double totalKcal, String source) {}

    @Transactional
    public void upsert(Long userId, UpsertReq req) {
        ZoneId zone = ZoneId.of(req.timezone());
        Instant start = req.localDate().atStartOfDay(zone).toInstant();
        Instant end = req.localDate().plusDays(1).atStartOfDay(zone).toInstant();

        UserDailyActivity e = repo.findByUserIdAndLocalDate(userId, req.localDate())
                .orElseGet(UserDailyActivity::new);

        e.setUserId(userId);
        e.setLocalDate(req.localDate());
        e.setTimezone(req.timezone());
        e.setDayStartUtc(start);
        e.setDayEndUtc(end);
        e.setSteps(req.steps() != null ? req.steps() : 0L);
        e.setTotalKcal(req.totalKcal() != null ? req.totalKcal() : 0.0);
        e.setSource(req.source() != null ? req.source() : "HEALTH_CONNECT");

        repo.save(e);
    }
}

