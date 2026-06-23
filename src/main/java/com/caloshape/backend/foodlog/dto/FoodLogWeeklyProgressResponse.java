package com.caloshape.backend.foodlog.dto;

import java.time.LocalDate;
import java.util.List;

public record FoodLogWeeklyProgressResponse(
        Period period,
        Summary summary,
        List<Day> days
) {
    public record Period(
            int weekOffset,
            String label,
            LocalDate startDate,
            LocalDate endDate
    ) {}

    public record Summary(
            double totalCalories,
            Double deltaPercent,
            String deltaDirection,
            String compareBasis,
            double average7Calories,
            double average15Calories,
            double average7FiberG,
            double average7SugarG,
            double average7SodiumMg
    ) {}

    public record Day(
            LocalDate date,
            String dayOfWeek,
            double totalKcal,
            double proteinG,
            double carbsG,
            double fatsG,
            double fiberG,
            double sugarG,
            double sodiumMg,
            double avgHealthScore
    ) {}
}
