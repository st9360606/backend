package com.calai.backend.foodlog.dto;

import java.util.List;

public record FoodLogListResponse(
        List<Item> items,
        Page page,
        FoodLogEnvelope.Trace trace
) {
    public record Item(
            String foodLogId,
            String status,
            String capturedLocalDate, // yyyy-MM-dd
            String capturedAtUtc,     // ISO-8601
            Nutrition nutrition
    ) {}

    public record Nutrition(
            String foodName,
            Double kcal,
            Double protein,
            Double fat,
            Double carbs
    ) {}

    public record Page(
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
