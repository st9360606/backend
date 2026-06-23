package com.caloshape.backend.foodlog.service;

import com.caloshape.backend.foodlog.dto.FoodLogWeeklyProgressResponse;
import com.caloshape.backend.foodlog.entity.UserDailyNutritionSummaryEntity;
import com.caloshape.backend.foodlog.job.retention.FoodLogRetentionProperties;
import com.caloshape.backend.foodlog.repo.FoodLogDailyNutritionAggregate;
import com.caloshape.backend.foodlog.repo.FoodLogRepository;
import com.caloshape.backend.foodlog.repo.UserDailyNutritionSummaryRepository;
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

    private static final int MAX_WEEK_OFFSET = 5;

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
        entity.setAvgHealthScore(clampHealthScore(safeDouble(agg.getAvgHealthScore())));
        entity.setMealCount(safeInt(agg.getMealCount()));
        entity.setLastRecomputedAtUtc(clock.instant());
        summaryRepo.save(entity);
    }

    @Transactional(readOnly = true)
    public FoodLogWeeklyProgressResponse getWeeklyProgress(Long userId, ZoneId zoneId, int weekOffset) {
        int safeOffset = Math.max(0, Math.min(MAX_WEEK_OFFSET, weekOffset));
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
            double fiber = row != null ? safeDouble(row.getTotalFiberG()) : 0d;
            double sugar = row != null ? safeDouble(row.getTotalSugarG()) : 0d;
            double sodium = row != null ? safeDouble(row.getTotalSodiumMg()) : 0d;
            double avgHealthScore = row != null ? safeDouble(row.getAvgHealthScore()) : 0d;
            currentTotal += kcal;
            days.add(new FoodLogWeeklyProgressResponse.Day(
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    round1(kcal),
                    round1(protein),
                    round1(carbs),
                    round1(fats),
                    round1(fiber),
                    round1(sugar),
                    round1(sodium),
                    round1(clampHealthScore(avgHealthScore))
            ));
        }

        LocalDate average7Start = (safeOffset == 0 ? today : weekEnd).minusDays(7);
        LocalDate averageAnchorExclusive = safeOffset == 0 ? today : weekEnd;
        LocalDate average15Start = averageAnchorExclusive.minusDays(15);

        NutritionAverage average7 = calculateNutritionAverage(
                summaryRepo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
                        userId,
                        average7Start,
                        averageAnchorExclusive.minusDays(1)
                )
        );
        NutritionAverage average15 = calculateNutritionAverage(
                summaryRepo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
                        userId,
                        average15Start,
                        averageAnchorExclusive.minusDays(1)
                )
        );

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
                        "PREVIOUS_WEEK",
                        average7.calories(),
                        average15.calories(),
                        average7.fiberG(),
                        average7.sugarG(),
                        average7.sodiumMg()
                ),
                days
        );
    }

    //每天上午 16:20清理一次，不是超過 63 天就即時刪
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

    private static NutritionAverage calculateNutritionAverage(List<UserDailyNutritionSummaryEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return NutritionAverage.ZERO;
        }

        int dataDays = 0;
        double totalCalories = 0d;
        double totalFiberG = 0d;
        double totalSugarG = 0d;
        double totalSodiumMg = 0d;

        for (UserDailyNutritionSummaryEntity row : rows) {
            if (row == null || safeInt(row.getMealCount()) <= 0) {
                continue;
            }
            dataDays++;
            totalCalories += safeDouble(row.getTotalKcal());
            totalFiberG += safeDouble(row.getTotalFiberG());
            totalSugarG += safeDouble(row.getTotalSugarG());
            totalSodiumMg += safeDouble(row.getTotalSodiumMg());
        }

        if (dataDays <= 0) {
            return NutritionAverage.ZERO;
        }

        return new NutritionAverage(
                round1(totalCalories / dataDays),
                round1(totalFiberG / dataDays),
                round1(totalSugarG / dataDays),
                round1(totalSodiumMg / dataDays)
        );
    }

    private record NutritionAverage(
            double calories,
            double fiberG,
            double sugarG,
            double sodiumMg
    ) {
        private static final NutritionAverage ZERO = new NutritionAverage(0d, 0d, 0d, 0d);
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
            case 4 -> "FOUR_WEEKS_AGO";
            case 5 -> "FIVE_WEEKS_AGO";
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

    private static double clampHealthScore(double value) {
        return Math.max(0d, Math.min(10d, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
