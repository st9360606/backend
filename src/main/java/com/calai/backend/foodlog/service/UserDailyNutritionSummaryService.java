package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogWeeklyProgressResponse;
import com.calai.backend.foodlog.entity.UserDailyNutritionSummaryEntity;
import com.calai.backend.foodlog.job.retention.FoodLogRetentionProperties;
import com.calai.backend.foodlog.repo.FoodLogDailyNutritionAggregate;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.UserDailyNutritionSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserDailyNutritionSummaryService {

    private final UserDailyNutritionSummaryRepository summaryRepo;
    private final FoodLogRepository foodLogRepo;
    private final FoodLogRetentionProperties retentionProperties;
    private final Clock clock;

    @Transactional
    public void recomputeDay(Long userId, LocalDate localDate) {
        if (userId == null || localDate == null) return;

        var aggOpt = foodLogRepo.aggregateDailyNutrition(userId, localDate);
        var existing = summaryRepo.findByUserIdAndLocalDate(userId, localDate).orElse(null);

        if (aggOpt.isEmpty() || safeInt(aggOpt.get().getMealCount()) <= 0) {
            if (existing != null) {
                summaryRepo.delete(existing);
            }
            return;
        }

        FoodLogDailyNutritionAggregate agg = aggOpt.get();
        UserDailyNutritionSummaryEntity entity = existing != null ? existing : new UserDailyNutritionSummaryEntity();
        entity.setUserId(userId);
        entity.setLocalDate(localDate);
        entity.setTimezone(safeTimezone(agg.getTimezone()));
        entity.setTotalKcal(safeDouble(agg.getTotalKcal()));
        entity.setTotalProteinG(safeDouble(agg.getTotalProteinG()));
        entity.setTotalCarbsG(safeDouble(agg.getTotalCarbsG()));
        entity.setTotalFatsG(safeDouble(agg.getTotalFatsG()));
        entity.setTotalFiberG(safeDouble(agg.getTotalFiberG()));
        entity.setTotalSugarG(safeDouble(agg.getTotalSugarG()));
        entity.setTotalSodiumMg(safeDouble(agg.getTotalSodiumMg()));
        entity.setMealCount(safeInt(agg.getMealCount()));
        entity.setLastRecomputedAtUtc(clock.instant());
        summaryRepo.save(entity);
    }

    @Transactional(readOnly = true)
    public FoodLogWeeklyProgressResponse getWeeklyProgress(Long userId, ZoneId zoneId, int weekOffset) {
        int safeOffset = Math.max(0, Math.min(3, weekOffset));
        LocalDate today = LocalDate.now(zoneId);
        LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate weekStart = currentWeekStart.minusWeeks(safeOffset);
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate previousWeekStart = weekStart.minusWeeks(1);
        LocalDate previousWeekEnd = previousWeekStart.plusDays(6);

        Map<LocalDate, UserDailyNutritionSummaryEntity> currentMap = toMap(
                summaryRepo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, weekStart, weekEnd)
        );
        List<UserDailyNutritionSummaryEntity> previousRows =
                summaryRepo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, previousWeekStart, previousWeekEnd);

        List<FoodLogWeeklyProgressResponse.Day> days = new ArrayList<>(7);
        double currentTotal = 0d;
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            UserDailyNutritionSummaryEntity row = currentMap.get(date);
            double kcal = row != null ? safeDouble(row.getTotalKcal()) : 0d;
            double protein = row != null ? safeDouble(row.getTotalProteinG()) : 0d;
            double carbs = row != null ? safeDouble(row.getTotalCarbsG()) : 0d;
            double fats = row != null ? safeDouble(row.getTotalFatsG()) : 0d;
            currentTotal += kcal;
            days.add(new FoodLogWeeklyProgressResponse.Day(
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    round1(kcal),
                    round1(protein),
                    round1(carbs),
                    round1(fats)
            ));
        }

        double previousTotal = previousRows.stream().mapToDouble(r -> safeDouble(r.getTotalKcal())).sum();
        Double deltaPercent = null;
        String deltaDirection = "NONE";
        if (previousTotal > 0d) {
            deltaPercent = round1(((currentTotal - previousTotal) / previousTotal) * 100d);
            if (deltaPercent > 0d) deltaDirection = "UP";
            else if (deltaPercent < 0d) deltaDirection = "DOWN";
            else deltaDirection = "FLAT";
        } else if (currentTotal == 0d) {
            deltaPercent = 0d;
            deltaDirection = "FLAT";
        }

        return new FoodLogWeeklyProgressResponse(
                new FoodLogWeeklyProgressResponse.Period(
                        safeOffset,
                        labelFor(safeOffset),
                        weekStart,
                        weekEnd
                ),
                new FoodLogWeeklyProgressResponse.Summary(
                        round1(currentTotal),
                        deltaPercent,
                        deltaDirection,
                        "PREVIOUS_WEEK"
                ),
                days
        );
    }

    //每天上午 16:20清理一次，不是超過 36 天就即時刪
    @Scheduled(cron = "0 20 8 * * *", zone = "UTC")
    @Transactional
    public void cleanupExpiredGlobal() {
        int days = Math.max(1, retentionProperties.getKeepDailySummaryDays());
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L);
        int deleted = summaryRepo.deleteByLocalDateBefore(cutoff);
        if (deleted > 0) {
            log.info("Daily nutrition summary cleanup: cutoff={}, deletedRows={}", cutoff, deleted);
        }
    }

    private static Map<LocalDate, UserDailyNutritionSummaryEntity> toMap(List<UserDailyNutritionSummaryEntity> rows) {
        Map<LocalDate, UserDailyNutritionSummaryEntity> out = new HashMap<>();
        for (UserDailyNutritionSummaryEntity row : rows) {
            out.put(row.getLocalDate(), row);
        }
        return out;
    }

    private static String labelFor(int weekOffset) {
        return switch (weekOffset) {
            case 0 -> "THIS_WEEK";
            case 1 -> "LAST_WEEK";
            case 2 -> "TWO_WEEKS_AGO";
            case 3 -> "THREE_WEEKS_AGO";
            default -> "THIS_WEEK";
        };
    }

    private static String safeTimezone(String raw) {
        return (raw == null || raw.isBlank()) ? "UTC" : raw.trim();
    }

    private static double safeDouble(Double value) {
        return value == null ? 0d : value;
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
