package com.calai.backend.water.job;

import com.calai.backend.water.repo.UserWaterDailyRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 全庫清理：保留「前 7 天（T-1..T-7）」，刪除小於 T-7 的資料。
 * 注意：排程不會刪到「今天 T」，因此實際保留至少是 T-7..T。
 */
@Component
public class WaterRetentionJob {

    private final UserWaterDailyRepository repo;

    public WaterRetentionJob(UserWaterDailyRepository repo) {
        this.repo = repo;
    }

    // 每天 03:00 UTC 執行
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void cleanup() {
        LocalDate todayUtc = LocalDate.now(ZoneId.of("UTC"));
        LocalDate cut = todayUtc.minusDays(7); // T-7

        // 刪除「早於 T-7」的所有資料（< T-7）
        repo.deleteByLocalDateBefore(cut);
    }
}
