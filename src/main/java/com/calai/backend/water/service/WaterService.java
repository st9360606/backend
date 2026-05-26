package com.calai.backend.water.service;

import com.calai.backend.foodlog.job.retention.FoodLogRetentionProperties;
import com.calai.backend.users.profile.repo.UserProfileRepository;
import com.calai.backend.water.dto.WaterDto;
import com.calai.backend.water.entity.UserWaterDaily;
import com.calai.backend.water.repo.UserWaterDailyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WaterService {

    private static final int MIN_KEEP_DAYS = 1;
    private static final int MAX_WEEK_OFFSET = 5;

    private final UserWaterDailyRepository repo;
    private final UserProfileRepository profileRepo;
    private final FoodLogRetentionProperties retentionProperties;

    public WaterService(
            UserWaterDailyRepository repo,
            UserProfileRepository profileRepo,
            FoodLogRetentionProperties retentionProperties
    ) {
        this.repo = repo;
        this.profileRepo = profileRepo;
        this.retentionProperties = retentionProperties;
    }

    /**
     * 取得「今天」的資料。如果沒有，就建立 0。
     * 進入點先按使用者、按時區清理舊資料。
     *
     * 保留規則：
     * - 與蛋白質/脂肪/碳水使用的 Progress daily nutrition summary retention 一致
     * - 預設 keepDailySummaryDays=36 時，保留 T-35..T，共 36 天
     */
    @Transactional
    public WaterDto.WaterSummaryDto getToday(Long userId, ZoneId zoneId) {
        cleanupOldForUser(userId, zoneId, progressRetentionDays());

        LocalDate localDate = LocalDate.now(zoneId);

        UserWaterDaily row = repo.findByUserIdAndLocalDate(userId, localDate)
                .orElseGet(() -> {
                    UserWaterDaily fresh = new UserWaterDaily();
                    fresh.setUserId(userId);
                    fresh.setLocalDate(localDate);
                    fresh.applyCups(0);
                    return repo.save(fresh);
                });

        return new WaterDto.WaterSummaryDto(
                row.getLocalDate(),
                row.getCups(),
                row.getMl(),
                row.getFlOz()
        );
    }

    /**
     * 調整今天的 cups (+1 或 -1)。
     * 後端負責 clamp >= 0，並回算 ml / flOz。
     *
     * 保留規則：
     * - 與蛋白質/脂肪/碳水使用的 Progress daily nutrition summary retention 一致
     * - 預設 keepDailySummaryDays=36 時，保留 T-35..T，共 36 天
     */
    @Transactional
    public WaterDto.WaterSummaryDto adjustToday(Long userId, ZoneId zoneId, int cupsDelta) {
        cleanupOldForUser(userId, zoneId, progressRetentionDays());

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

        return new WaterDto.WaterSummaryDto(
                saved.getLocalDate(),
                saved.getCups(),
                saved.getMl(),
                saved.getFlOz()
        );
    }

    /**
     * 單用戶清理（以使用者時區計算 today）
     *
     * keepPreviousDays = 36 時：
     * - 保留 T-35..T，含今天，共 36 天
     * - 刪除 < T-35
     */
    @Transactional
    void cleanupOldForUser(Long userId, ZoneId zoneId, int keepPreviousDays) {
        LocalDate today = LocalDate.now(zoneId);
        long daysToSubtract = Math.max(MIN_KEEP_DAYS, keepPreviousDays) - 1L;
        LocalDate cutoffInclusive = today.minusDays(daysToSubtract);
        repo.deleteByUserIdAndLocalDateBefore(userId, cutoffInclusive);
    }

    private int progressRetentionDays() {
        return Math.max(MIN_KEEP_DAYS, retentionProperties.getKeepDailySummaryDays());
    }


    @Transactional(readOnly = true)
    public WaterDto.WaterWeeklyChartDto getWeeklyChart(Long userId, ZoneId zoneId, int weekOffset) {
        int safeOffset = Math.max(0, Math.min(MAX_WEEK_OFFSET, weekOffset));
        LocalDate today = LocalDate.now(zoneId);
        LocalDate currentWeekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
        LocalDate startDate = currentWeekStart.minusWeeks(safeOffset);
        LocalDate endDate = startDate.plusDays(6);

        List<UserWaterDaily> rows = repo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
                userId,
                startDate,
                endDate
        );

        Map<LocalDate, UserWaterDaily> rowMap = rows.stream()
                .collect(Collectors.toMap(UserWaterDaily::getLocalDate, Function.identity()));

        int goalMl = profileRepo.findByUserId(userId)
                .map(p -> p.getWaterMl() == null ? 0 : Math.max(p.getWaterMl(), 0))
                .orElse(0);

        List<WaterDto.WaterSummaryDto> days = startDate.datesUntil(endDate.plusDays(1))
                .map(date -> {
                    UserWaterDaily row = rowMap.get(date);
                    if (row == null) {
                        return new WaterDto.WaterSummaryDto(date, 0, 0, 0);
                    }
                    return new WaterDto.WaterSummaryDto(
                            row.getLocalDate(),
                            row.getCups(),
                            row.getMl(),
                            row.getFlOz()
                    );
                })
                .toList();

        return new WaterDto.WaterWeeklyChartDto(goalMl, days);
    }
}
