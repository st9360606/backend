package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLogEnvelope(
        String foodLogId,
        String status,
        String degradeLevel,
        NutritionResult nutritionResult,
        Task task,
        ApiError error,
        Trace trace
) {
    public record NutritionResult(
            String foodName,
            Quantity quantity,
            Nutrients nutrients,
            Integer healthScore,
            Double confidence,
            Source source
    ) {}

    public record Quantity(Double value, String unit) {}
    public record Nutrients(Double kcal, Double protein, Double fat, Double carbs, Double fiber, Double sugar, Double sodium) {}
    public record Source(String method, String provider) {}

    public record Task(String taskId, Integer pollAfterSec) {}
    public record ApiError(String errorCode, String clientAction, Integer retryAfterSec) {}
    public record Trace(String requestId) {}
}

