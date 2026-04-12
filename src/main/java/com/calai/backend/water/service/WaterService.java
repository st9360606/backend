package com.calai.backend.water.service;

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

    // 保留今天以前的 7 天，再加上今天，共 8 天（T-7..T）
    private static final int DEFAULT_KEEP_PREVIOUS_DAYS = 7;

    private final UserWaterDailyRepository repo;

    private final UserProfileRepository profileRepo;

    public WaterService(UserWaterDailyRepository repo, UserProfileRepository profileRepo) {
        this.repo = repo;
        this.profileRepo = profileRepo;
    }

    /**
     * 取得「今天」的資料。如果沒有，就建立 0。
     * 進入點先按使用者、按時區清理舊資料。
     *
     * 保留規則：
     * - 保留 T-7..T（共 8 天，含今天）
     * - 刪除 < T-7
     */
    @Transactional
    public WaterDto.WaterSummaryDto getToday(Long userId, ZoneId zoneId) {
        cleanupOldForUser(userId, zoneId, DEFAULT_KEEP_PREVIOUS_DAYS);

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
     * - 保留 T-7..T（共 8 天，含今天）
     * - 刪除 < T-7
     */
    @Transactional
    public WaterDto.WaterSummaryDto adjustToday(Long userId, ZoneId zoneId, int cupsDelta) {
        cleanupOldForUser(userId, zoneId, DEFAULT_KEEP_PREVIOUS_DAYS);

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
     * keepPreviousDays = 7 時：
     * - 保留 T-7..T（共 8 天，含今天）
     * - 刪除 < T-7
     */
    @Transactional
    void cleanupOldForUser(Long userId, ZoneId zoneId, int keepPreviousDays) {
        LocalDate today = LocalDate.now(zoneId);
        LocalDate cutoffExclusive = today.minusDays(keepPreviousDays); // T-7
        repo.deleteByUserIdAndLocalDateBefore(userId, cutoffExclusive);
    }


    @Transactional(readOnly = true)
    public WaterDto.WaterWeeklyChartDto getRecent7Days(Long userId, ZoneId zoneId) {
        LocalDate today = LocalDate.now(zoneId);
        LocalDate startDate = today.minusDays(6);

        List<UserWaterDaily> rows = repo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
                userId,
                startDate,
                today
        );

        Map<LocalDate, UserWaterDaily> rowMap = rows.stream()
                .collect(Collectors.toMap(UserWaterDaily::getLocalDate, Function.identity()));

        int goalMl = profileRepo.findByUserId(userId)
                .map(p -> p.getWaterMl() == null ? 0 : Math.max(p.getWaterMl(), 0))
                .orElse(0);

        List<WaterDto.WaterSummaryDto> days = startDate.datesUntil(today.plusDays(1))
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
