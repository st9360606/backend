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

    // 保留「前 7 天（T-1..T-7）」，刪除 < T-7
    private static final int DEFAULT_KEEP_DAYS = 7;

    private final UserWaterDailyRepository repo;

    public WaterService(UserWaterDailyRepository repo) {
        this.repo = repo;
    }

    /**
     * 取得「今天」的資料。如果沒有，就建立 0。
     * 進入點先「按使用者、按時區」清理 < (today-7) 的資料（保留 T-7）。
     */
    @Transactional
    public WaterDtos.WaterSummaryDto getToday(Long userId, ZoneId zoneId) {
        cleanupOldForUser(userId, zoneId, DEFAULT_KEEP_DAYS);

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
     * 進入點同樣採「刪除 < (today-7)」，保留 T-7。
     */
    @Transactional
    public WaterDtos.WaterSummaryDto adjustToday(Long userId, ZoneId zoneId, int cupsDelta) {
        cleanupOldForUser(userId, zoneId, DEFAULT_KEEP_DAYS);

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
     * 單用戶清理（以使用者時區計算 today）：
     * 定義：保留 T-1..T-7；刪除 < T-7。
     */
    @Transactional
    void cleanupOldForUser(Long userId, ZoneId zoneId, int keepDays) {
        LocalDate today = LocalDate.now(zoneId);
        LocalDate cutoffExclusive = today.minusDays(keepDays); // T-7
        // 只刪該 user，且「不含截止日」→ < T-7
        repo.deleteByUserIdAndLocalDateBefore(userId, cutoffExclusive);
    }
}
