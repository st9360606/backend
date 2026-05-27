package com.calai.backend.foodlog.dto;

import java.util.List;

public record ProgressAveragesResponse(
        List<RangeAverage> ranges
) {
    public record RangeAverage(
            int days,
            double caloriesKcal,
            double proteinG,
            double carbsG,
            double fatsG,
            double fiberG,
            double sugarG,
            double sodiumMg,
            double workoutKcal,
            double waterMl,
            double healthScore,
            double steps
    ) {}
}
