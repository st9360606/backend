package com.calai.backend.foodlog.dto;

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
            String compareBasis
    ) {}

    public record Day(
            LocalDate date,
            String dayOfWeek,
            double totalKcal,
            double proteinG,
            double carbsG,
            double fatsG
    ) {}
}
