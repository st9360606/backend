package com.caloshape.backend.foodlog.service;

import com.caloshape.backend.foodlog.dto.ProgressAveragesResponse;
import com.caloshape.backend.foodlog.entity.UserDailyNutritionSummaryEntity;
import com.caloshape.backend.foodlog.repo.UserDailyNutritionSummaryRepository;
import com.caloshape.backend.users.activity.entity.UserDailyActivity;
import com.caloshape.backend.users.activity.repo.UserDailyActivityRepository;
import com.caloshape.backend.water.entity.UserWaterDaily;
import com.caloshape.backend.water.repo.UserWaterDailyRepository;
import com.caloshape.backend.workout.entity.UserDailyWorkoutSummaryEntity;
import com.caloshape.backend.workout.repo.UserDailyWorkoutSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ProgressAveragesService {

    private static final int[] SUPPORTED_WINDOWS = {7, 15, 30, 60};

    private final UserDailyNutritionSummaryRepository nutritionRepo;
    private final UserDailyWorkoutSummaryRepository workoutRepo;
    private final UserWaterDailyRepository waterRepo;
    private final UserDailyActivityRepository activityRepo;

    @Transactional(readOnly = true)
    public ProgressAveragesResponse getAverages(Long userId, ZoneId zoneId) {
        LocalDate today = LocalDate.now(zoneId);
        LocalDate oldestStart = today.minusDays(60);
        LocalDate yesterday = today.minusDays(1);

        List<UserDailyNutritionSummaryEntity> nutritionRows = nutritionRepo
                .findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, oldestStart, yesterday);
        List<UserDailyWorkoutSummaryEntity> workoutRows = workoutRepo
                .findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, oldestStart, yesterday);
        List<UserWaterDaily> waterRows = waterRepo
                .findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, oldestStart, yesterday);
        List<UserDailyActivity> activityRows = activityRepo
                .findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, oldestStart, yesterday);

        List<ProgressAveragesResponse.RangeAverage> ranges = new ArrayList<>(SUPPORTED_WINDOWS.length);
        for (int windowDays : SUPPORTED_WINDOWS) {
            LocalDate from = today.minusDays(windowDays);
            ranges.add(calculateWindowAverage(
                    windowDays,
                    from,
                    yesterday,
                    nutritionRows,
                    workoutRows,
                    waterRows,
                    activityRows
            ));
        }

        return new ProgressAveragesResponse(ranges);
    }

    private ProgressAveragesResponse.RangeAverage calculateWindowAverage(
            int windowDays,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            List<UserDailyNutritionSummaryEntity> nutritionRows,
            List<UserDailyWorkoutSummaryEntity> workoutRows,
            List<UserWaterDaily> waterRows,
            List<UserDailyActivity> activityRows
    ) {
        NutritionAverage nutrition = calculateNutritionAverage(nutritionRows, fromInclusive, toInclusive);
        double workoutKcal = calculateWorkoutAverage(workoutRows, fromInclusive, toInclusive);
        double waterMl = calculateWaterAverage(waterRows, fromInclusive, toInclusive);
        double steps = calculateStepsAverage(activityRows, fromInclusive, toInclusive);

        return new ProgressAveragesResponse.RangeAverage(
                windowDays,
                nutrition.caloriesKcal(),
                nutrition.proteinG(),
                nutrition.carbsG(),
                nutrition.fatsG(),
                nutrition.fiberG(),
                nutrition.sugarG(),
                nutrition.sodiumMg(),
                workoutKcal,
                waterMl,
                nutrition.healthScore(),
                steps
        );
    }

    private NutritionAverage calculateNutritionAverage(
            List<UserDailyNutritionSummaryEntity> rows,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        int dataDays = 0;
        double calories = 0d;
        double protein = 0d;
        double carbs = 0d;
        double fats = 0d;
        double fiber = 0d;
        double sugar = 0d;
        double sodium = 0d;
        double healthScore = 0d;

        for (UserDailyNutritionSummaryEntity row : rows) {
            if (row == null || !inRange(row.getLocalDate(), fromInclusive, toInclusive)) {
                continue;
            }
            if (safeInt(row.getMealCount()) <= 0) {
                continue;
            }
            dataDays++;
            calories += safeDouble(row.getTotalKcal());
            protein += safeDouble(row.getTotalProteinG());
            carbs += safeDouble(row.getTotalCarbsG());
            fats += safeDouble(row.getTotalFatsG());
            fiber += safeDouble(row.getTotalFiberG());
            sugar += safeDouble(row.getTotalSugarG());
            sodium += safeDouble(row.getTotalSodiumMg());
            healthScore += clampHealthScore(safeDouble(row.getAvgHealthScore()));
        }

        if (dataDays <= 0) {
            return NutritionAverage.ZERO;
        }

        return new NutritionAverage(
                round1(calories / dataDays),
                round1(protein / dataDays),
                round1(carbs / dataDays),
                round1(fats / dataDays),
                round1(fiber / dataDays),
                round1(sugar / dataDays),
                round1(sodium / dataDays),
                round1(healthScore / dataDays)
        );
    }

    private double calculateWorkoutAverage(
            List<UserDailyWorkoutSummaryEntity> rows,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        int dataDays = 0;
        double total = 0d;

        for (UserDailyWorkoutSummaryEntity row : rows) {
            if (row == null || !inRange(row.getLocalDate(), fromInclusive, toInclusive)) {
                continue;
            }
            double burned = safeDouble(row.getTotalBurnedKcal());
            if (burned <= 0d) {
                continue;
            }
            dataDays++;
            total += burned;
        }

        return dataDays <= 0 ? 0d : round1(total / dataDays);
    }

    private double calculateWaterAverage(
            List<UserWaterDaily> rows,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        int dataDays = 0;
        double total = 0d;

        for (UserWaterDaily row : rows) {
            if (row == null || !inRange(row.getLocalDate(), fromInclusive, toInclusive)) {
                continue;
            }
            int ml = safeInt(row.getMl());
            if (ml <= 0) {
                continue;
            }
            dataDays++;
            total += ml;
        }

        return dataDays <= 0 ? 0d : round1(total / dataDays);
    }

    private double calculateStepsAverage(
            List<UserDailyActivity> rows,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        int dataDays = 0;
        double total = 0d;

        for (UserDailyActivity row : rows) {
            if (row == null || !inRange(row.getLocalDate(), fromInclusive, toInclusive)) {
                continue;
            }
            long steps = safeLong(row.getSteps());
            if (steps <= 0L) {
                continue;
            }
            dataDays++;
            total += steps;
        }

        return dataDays <= 0 ? 0d : round1(total / dataDays);
    }

    private static boolean inRange(LocalDate date, LocalDate fromInclusive, LocalDate toInclusive) {
        return date != null && !date.isBefore(fromInclusive) && !date.isAfter(toInclusive);
    }

    private static double safeDouble(Number value) {
        return value == null ? 0d : value.doubleValue();
    }

    private static int safeInt(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private static long safeLong(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private static double clampHealthScore(double value) {
        return Math.max(0d, Math.min(10d, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private record NutritionAverage(
            double caloriesKcal,
            double proteinG,
            double carbsG,
            double fatsG,
            double fiberG,
            double sugarG,
            double sodiumMg,
            double healthScore
    ) {
        private static final NutritionAverage ZERO = new NutritionAverage(0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d);
    }
}
