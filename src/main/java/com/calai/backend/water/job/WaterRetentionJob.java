package com.calai.backend.water.job;

import com.calai.backend.water.service.WaterService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天清一次，刪除 7 天前的紀錄，避免資料表無限長大
 */
@Component
public class WaterRetentionJob {

    private final WaterService service;

    public WaterRetentionJob(WaterService service) {
        this.service = service;
    }

    // 每天 03:00 UTC
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void cleanup() {
        service.cleanupOld(7);
    }
}
