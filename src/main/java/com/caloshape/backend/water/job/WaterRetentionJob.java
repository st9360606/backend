package com.caloshape.backend.water.job;

import com.caloshape.backend.foodlog.job.retention.FoodLogRetentionProperties;
import com.caloshape.backend.water.repo.UserWaterDailyRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 全庫清理：保留天數與蛋白質/脂肪/碳水的 Progress daily nutrition summary 一致。
 * 預設 keepDailySummaryDays=63 時，保留 T-62..T，共 63 天。
 */
@Component
public class WaterRetentionJob {

    private static final int MIN_KEEP_DAYS = 1;

    private final UserWaterDailyRepository repo;
    private final FoodLogRetentionProperties retentionProperties;

    public WaterRetentionJob(UserWaterDailyRepository repo, FoodLogRetentionProperties retentionProperties) {
        this.repo = repo;
        this.retentionProperties = retentionProperties;
    }

    // 每天 03:00 UTC 執行
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void cleanup() {
        int keepDays = Math.max(MIN_KEEP_DAYS, retentionProperties.getKeepDailySummaryDays());
        LocalDate todayUtc = LocalDate.now(ZoneId.of("UTC"));
        LocalDate cutoffInclusive = todayUtc.minusDays(keepDays - 1L);

        // 刪除「早於 cutoffInclusive」的所有資料（< cutoffInclusive）
        repo.deleteByLocalDateBefore(cutoffInclusive);
    }
}
