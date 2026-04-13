package com.calai.backend.workout.dto;

import java.time.LocalDate;
import java.util.List;

public record WorkoutWeeklyProgressResponse(
        Summary summary,
        List<Day> days
) {
    public record Summary(
            double todayBurnedKcal,
            int goalKcal,
            int averageKcal,
            Double deltaPercent,
            String deltaDirection,
            String compareBasis
    ) {}

    public record Day(
            LocalDate date,
            String dayOfWeek,
            double totalBurnedKcal,
            double workoutKcal,
            double activeKcal
    ) {}
}
