package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record FoodLogListResponse(
        List<Item> items,
        Page page,
        FoodLogEnvelope.Trace trace
) {
    public record Item(
            String foodLogId,
            String status,
            String capturedLocalDate,
            String capturedAtUtc,
            Nutrition nutrition
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
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
            Double confidence,
            List<String> warnings,
            String degradedReason
    ) {}

    public record Page(
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
