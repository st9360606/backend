package com.calai.backend.water.service;

import com.calai.backend.water.dto.WaterDtos;
import com.calai.backend.water.entity.UserWaterDaily;
import com.calai.backend.water.repo.UserWaterDailyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class WaterService {

    private final UserWaterDailyRepository repo;

    public WaterService(UserWaterDailyRepository repo) {
        this.repo = repo;
    }

    /**
     * 取得「今天」的資料。如果沒有，就建立 0。
     * @param userId 使用者ID (從你的 JWT / SecurityContext 拿)
     * @param zoneId 使用者時區（例如 Asia/Taipei、America/New_York）
     */
    @Transactional
    public WaterDtos.WaterSummaryDto getToday(Long userId, ZoneId zoneId) {
        LocalDate localDate = LocalDate.now(zoneId);

        UserWaterDaily row = repo.findByUserIdAndLocalDate(userId, localDate)
                .orElseGet(() -> {
                    UserWaterDaily fresh = new UserWaterDaily();
                    fresh.setUserId(userId);
                    fresh.setLocalDate(localDate);
                    fresh.applyCups(0); // cups=0, ml=0, flOz=0
                    return repo.save(fresh);
                });

        return new WaterDtos.WaterSummaryDto(
                row.getLocalDate(),
                row.getCups(),
                row.getMl(),
                row.getFlOz()
        );
    }

    /**
     * 調整今天的 cups (+1 或 -1)。
     * 後端負責 clamp >=0 並回算 ml / fl_oz。
     */
    @Transactional
    public WaterDtos.WaterSummaryDto adjustToday(Long userId, ZoneId zoneId, int cupsDelta) {
        LocalDate localDate = LocalDate.now(zoneId);

        UserWaterDaily row = repo.findByUserIdAndLocalDate(userId, localDate)
                .orElseGet(() -> {
                    UserWaterDaily fresh = new UserWaterDaily();
                    fresh.setUserId(userId);
                    fresh.setLocalDate(localDate);
                    fresh.applyCups(0);
                    return fresh;
                });

        int newCups = row.getCups() + cupsDelta;
        row.applyCups(newCups);

        UserWaterDaily saved = repo.save(row);

        return new WaterDtos.WaterSummaryDto(
                saved.getLocalDate(),
                saved.getCups(),
                saved.getMl(),
                saved.getFlOz()
        );
    }

    /**
     * 刪除 7 天前(含)更舊的資料，避免表爆炸。
     * e.g. now=2025-10-25 -> 刪除 local_date < 2025-10-18
     */
    @Transactional
    public void cleanupOld(int keepDays) {
        LocalDate cutoff = LocalDate.now(ZoneId.of("UTC")).minusDays(keepDays);
        repo.deleteByLocalDateBefore(cutoff);
    }
}
