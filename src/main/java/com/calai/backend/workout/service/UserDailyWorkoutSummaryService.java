package com.calai.backend.workout.service;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.repo.UserDailyActivityRepository;
import com.calai.backend.users.profile.entity.UserProfile;
import com.calai.backend.users.profile.repo.UserProfileRepository;
import com.calai.backend.workout.dto.WorkoutWeeklyProgressResponse;
import com.calai.backend.workout.entity.UserDailyWorkoutSummaryEntity;
import com.calai.backend.workout.repo.UserDailyWorkoutSummaryRepository;
import com.calai.backend.workout.repo.WorkoutSessionRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserDailyWorkoutSummaryService {

    private static final int DEFAULT_DAILY_WORKOUT_GOAL_KCAL = 450;

    private final UserDailyWorkoutSummaryRepository summaryRepo;
    private final WorkoutSessionRepo workoutSessionRepo;
    private final UserDailyActivityRepository dailyActivityRepo;
    private final UserProfileRepository profileRepo;
    private final Clock clock;

    @Transactional
    public void recomputeDay(Long userId, LocalDate localDate, ZoneId zoneId) {
        if (userId == null || localDate == null || zoneId == null) return;

        Instant start = localDate.atStartOfDay(zoneId).toInstant();
        Instant end = localDate.plusDays(1).atStartOfDay(zoneId).toInstant();

        double workoutKcal = safeDouble(
                workoutSessionRepo.sumKcalByUserIdAndStartedAtBetween(userId, start, end)
        );

        int sessionCount = Math.toIntExact(
                workoutSessionRepo.countByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                        userId, start, end
                )
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

        UserDailyWorkoutSummaryEntity entity =
                existing != null ? existing : new UserDailyWorkoutSummaryEntity();

        entity.setUserId(userId);
        entity.setLocalDate(localDate);
        entity.setTimezone(zoneId.getId());
        entity.setWorkoutKcal(round3(workoutKcal));
        entity.setActivityKcal(round3(activityKcal));
        entity.setTotalBurnedKcal(round3(total));
        entity.setWorkoutSessionCount(sessionCount);
        entity.setLastRecomputedAtUtc(clock.instant());

        summaryRepo.save(entity);
    }

    /**
     * 讀取前先補近 8 天 materialize，避免剛上線時圖表為空。
     * 近 8 天是因為 retention 要保留 8 天內資料。
     */
    @Transactional
    public WorkoutWeeklyProgressResponse getWeeklyProgress(Long userId, ZoneId zoneId) {
        LocalDate today = LocalDate.now(zoneId);

        // 自我修復 / backfill 最近 8 天
        for (int i = 7; i >= 0; i--) {
            recomputeDay(userId, today.minusDays(i), zoneId);
        }

        LocalDate start = today.minusDays(6);
        LocalDate yesterday = today.minusDays(1);

        Map<LocalDate, UserDailyWorkoutSummaryEntity> rowMap = toMap(
                summaryRepo.findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(userId, start, today)
        );

        List<WorkoutWeeklyProgressResponse.Day> days = new ArrayList<>(7);
        double sum7 = 0d;

        for (int i = 0; i < 7; i++) {
            LocalDate date = start.plusDays(i);
            UserDailyWorkoutSummaryEntity row = rowMap.get(date);

            double total = row != null ? safeDouble(row.getTotalBurnedKcal()) : 0d;
            double workout = row != null ? safeDouble(row.getWorkoutKcal()) : 0d;
            double activity = row != null ? safeDouble(row.getActivityKcal()) : 0d;

            sum7 += total;

            days.add(new WorkoutWeeklyProgressResponse.Day(
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    round1(total),
                    round1(workout),
                    round1(activity)
            ));
        }

        double todayBurned = rowMap.get(today) != null ? safeDouble(rowMap.get(today).getTotalBurnedKcal()) : 0d;
        double yesterdayBurned = rowMap.get(yesterday) != null ? safeDouble(rowMap.get(yesterday).getTotalBurnedKcal()) : 0d;

        Double deltaPercent = calculateDeltaPercent(todayBurned, yesterdayBurned);
        String deltaDirection = directionOf(deltaPercent);

        int goalKcal = profileRepo.findByUserId(userId)
                .map(UserProfile::getDailyWorkoutGoalKcal)
                .map(v -> Math.max(0, v))
                .orElse(DEFAULT_DAILY_WORKOUT_GOAL_KCAL);

        int averageKcal = (int) Math.round(sum7 / 7d);

        return new WorkoutWeeklyProgressResponse(
                new WorkoutWeeklyProgressResponse.Summary(
                        round1(todayBurned),
                        goalKcal,
                        averageKcal,
                        deltaPercent != null ? round1(deltaPercent) : null,
                        deltaDirection,
                        "YESTERDAY"
                ),
                days
        );
    }

    @Scheduled(cron = "0 25 8 * * *", zone = "UTC")
    @Transactional
    public void cleanupExpiredGlobal() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(7);
        int deleted = summaryRepo.deleteByLocalDateBefore(cutoff);
        if (deleted > 0) {
            log.info("Daily workout summary cleanup: cutoff={}, deletedRows={}", cutoff, deleted);
        }
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
