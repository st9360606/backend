package com.caloshape.backend.workout.service;

import com.caloshape.backend.foodlog.job.retention.FoodLogRetentionProperties;
import com.caloshape.backend.users.activity.entity.UserDailyActivity;
import com.caloshape.backend.users.activity.repo.UserDailyActivityRepository;
import com.caloshape.backend.users.profile.entity.UserProfile;
import com.caloshape.backend.users.profile.repo.UserProfileRepository;
import com.caloshape.backend.workout.dto.WorkoutWeeklyProgressResponse;
import com.caloshape.backend.workout.entity.UserDailyWorkoutSummaryEntity;
import com.caloshape.backend.workout.repo.UserDailyWorkoutSummaryRepository;
import com.caloshape.backend.workout.repo.WorkoutSessionRepo;
import com.caloshape.backend.weight.repo.WeightTimeseriesRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserDailyWorkoutSummaryService {

    private static final int DEFAULT_DAILY_WORKOUT_GOAL_KCAL = 450;
    private static final int MAX_WEEK_OFFSET = 5;
    private static final double STEP_KCAL_COEFFICIENT = 0.0005d;

    private final UserDailyWorkoutSummaryRepository summaryRepo;
    private final WorkoutSessionRepo workoutSessionRepo;
    private final UserDailyActivityRepository dailyActivityRepo;
    private final UserProfileRepository profileRepo;
    private final WeightTimeseriesRepo weightTimeseriesRepo;
    private final FoodLogRetentionProperties retentionProperties;
    private final Clock clock;

    @Transactional
    public void recomputeDay(Long userId, LocalDate localDate, ZoneId zoneId) {
        if (userId == null || localDate == null || zoneId == null) return;

        double workoutKcal = safeDouble(
                workoutSessionRepo.sumKcalByUserIdAndLocalDate(userId, localDate)
        );

        int sessionCount = Math.toIntExact(
                workoutSessionRepo.countByUserIdAndLocalDate(userId, localDate)
        );

        double activityKcal = dailyActivityRepo.findByUserIdAndLocalDate(userId, localDate)
                .map(UserDailyActivity::getActiveKcal)
                .map(this::safeDouble)
                .orElse(0d);

        double total = workoutKcal + activityKcal;

        UserDailyWorkoutSummaryEntity existing = summaryRepo.findByUserIdAndLocalDate(userId, localDate)
                .orElse(null);

        if (total <= 0d && sessionCount <= 0) {
            if (existing != null) {
                summaryRepo.delete(existing);
            }
            return;
        }

        var now = clock.instant();

        UserDailyWorkoutSummaryEntity entity =
                existing != null ? existing : new UserDailyWorkoutSummaryEntity();

        if (existing == null) {
            entity.setCreatedAtUtc(now);
        }

        entity.setUserId(userId);
        entity.setLocalDate(localDate);
        entity.setTimezone(zoneId.getId());
        entity.setWorkoutKcal(round3(workoutKcal));
        entity.setActivityKcal(round3(activityKcal));
        entity.setTotalBurnedKcal(round3(total));
        entity.setWorkoutSessionCount(sessionCount);
        entity.setLastRecomputedAtUtc(now);
        entity.setUpdatedAtUtc(now);

        summaryRepo.save(entity);
    }

    /**
     * 依 ProgressScreen 的週切換讀取 Sunday..Saturday 的 7 天資料。
     * - weekOffset=0：本週
     * - weekOffset=1：上週
     * - weekOffset=2：兩週前
     * - weekOffset=3：三週前
     */
    @Transactional
    public WorkoutWeeklyProgressResponse getWeeklyProgress(Long userId, ZoneId zoneId, int weekOffset) {
        int safeOffset = Math.max(0, Math.min(MAX_WEEK_OFFSET, weekOffset));
        LocalDate today = LocalDate.now(zoneId);
        LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate start = currentWeekStart.minusWeeks(safeOffset);
        LocalDate end = start.plusDays(6);

        for (int i = 0; i < 7; i++) {
            recomputeDay(userId, start.plusDays(i), zoneId);
        }

        Map<LocalDate, UserDailyWorkoutSummaryEntity> rowMap = toMap(
                summaryRepo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, start, end)
        );

        List<WorkoutWeeklyProgressResponse.Day> days = new ArrayList<>(7);

        for (int i = 0; i < 7; i++) {
            LocalDate date = start.plusDays(i);
            UserDailyWorkoutSummaryEntity row = rowMap.get(date);

            double total = row != null ? safeDouble(row.getTotalBurnedKcal()) : 0d;
            double workout = row != null ? safeDouble(row.getWorkoutKcal()) : 0d;
            double activity = row != null ? safeDouble(row.getActivityKcal()) : 0d;

            days.add(new WorkoutWeeklyProgressResponse.Day(
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    round1(total),
                    round1(workout),
                    round1(activity)
            ));
        }

        LocalDate displayDate = safeOffset == 0 ? today : end;
        LocalDate compareDate = displayDate.minusDays(1);
        double displayBurned = rowMap.get(displayDate) != null ? safeDouble(rowMap.get(displayDate).getTotalBurnedKcal()) : 0d;
        double compareBurned = rowMap.get(compareDate) != null ? safeDouble(rowMap.get(compareDate).getTotalBurnedKcal()) : 0d;

        Double deltaPercent = calculateDeltaPercent(displayBurned, compareBurned);
        String deltaDirection = directionOf(deltaPercent);

        UserProfile profile = profileRepo.findByUserId(userId).orElse(null);
        Double latestWeightKg = weightTimeseriesRepo.findLatest(userId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(weight -> weight.getWeightKg() == null ? null : weight.getWeightKg().doubleValue())
                .orElse(null);

        int goalKcal = calculateTotalActivityGoalKcal(
                profile != null ? profile.getDailyWorkoutGoalKcal() : null,
                profile != null ? profile.getDailyStepGoal() : null,
                latestWeightKg
        );

        for (int i = 7; i >= 1; i--) {
            recomputeDay(userId, displayDate.minusDays(i), zoneId);
        }

        int averageKcal = calculateAverageKcal(
                summaryRepo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
                        userId,
                        displayDate.minusDays(7),
                        displayDate.minusDays(1)
                )
        );

        return new WorkoutWeeklyProgressResponse(
                new WorkoutWeeklyProgressResponse.Summary(
                        round1(displayBurned),
                        goalKcal,
                        averageKcal,
                        deltaPercent != null ? round1(deltaPercent) : null,
                        deltaDirection,
                        safeOffset == 0 ? "YESTERDAY" : "PREVIOUS_DAY"
                ),
                days
        );
    }

    @Scheduled(cron = "0 25 8 * * *", zone = "UTC")
    @Transactional
    public void cleanupExpiredGlobal() {
        int days = Math.max(1, retentionProperties.getKeepDailySummaryDays());
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L);
        int deleted = summaryRepo.deleteByLocalDateBefore(cutoff);
        if (deleted > 0) {
            log.info("Daily workout summary cleanup: cutoff={}, deletedRows={}", cutoff, deleted);
        }
    }

    private int calculateAverageKcal(List<UserDailyWorkoutSummaryEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        List<UserDailyWorkoutSummaryEntity> dataRows = rows.stream()
                .filter(row -> row != null && safeDouble(row.getTotalBurnedKcal()) > 0d)
                .toList();

        if (dataRows.isEmpty()) {
            return 0;
        }

        double totalKcal = dataRows.stream()
                .mapToDouble(row -> safeDouble(row.getTotalBurnedKcal()))
                .sum();

        return (int) Math.round(totalKcal / dataRows.size());
    }

    static int calculateTotalActivityGoalKcal(
            Integer workoutGoalKcal,
            Integer stepGoal,
            Double weightKg
    ) {
        int safeWorkoutGoal = workoutGoalKcal == null
                ? DEFAULT_DAILY_WORKOUT_GOAL_KCAL
                : Math.max(0, workoutGoalKcal);

        if (stepGoal == null || stepGoal <= 0 || weightKg == null || weightKg <= 0d) {
            return safeWorkoutGoal;
        }

        long stepGoalKcal = Math.round(weightKg * stepGoal * STEP_KCAL_COEFFICIENT);
        long totalGoalKcal = safeWorkoutGoal + Math.max(0L, stepGoalKcal);
        return (int) Math.min(Integer.MAX_VALUE, totalGoalKcal);
    }

    private Map<LocalDate, UserDailyWorkoutSummaryEntity> toMap(List<UserDailyWorkoutSummaryEntity> rows) {
        Map<LocalDate, UserDailyWorkoutSummaryEntity> out = new HashMap<>();
        for (UserDailyWorkoutSummaryEntity row : rows) {
            out.put(row.getLocalDate(), row);
        }
        return out;
    }

    private Double calculateDeltaPercent(double today, double yesterday) {
        if (today == 0d && yesterday == 0d) return 0d;
        if (yesterday == 0d) return 100d;
        return ((today - yesterday) / yesterday) * 100d;
    }

    private String directionOf(Double delta) {
        if (delta == null) return "NONE";
        if (delta > 0d) return "UP";
        if (delta < 0d) return "DOWN";
        return "FLAT";
    }

    private double safeDouble(Number value) {
        return value == null ? 0d : value.doubleValue();
    }

    private double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private double round3(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
