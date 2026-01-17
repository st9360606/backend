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

    /**
     * ✅ 擴充欄位（向下相容：新增欄位不會破壞舊 client）
     */
    public record Nutrition(
            String foodName,
            Double kcal,
            Double protein,
            Double fat,
            Double carbs,
            Double fiber,
            Double sugar,
            Double sodium,
            Integer healthScore,
            Double confidence
    ) {}

    public record Page(
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
