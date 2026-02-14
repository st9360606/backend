package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLogEnvelope(
        String foodLogId,
        String status,
        String degradeLevel,
        String tierUsed,
        boolean fromCache,
        NutritionResult nutritionResult,
        Task task,
        ApiError error,
        Trace trace
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NutritionResult(
            String foodName,
            Quantity quantity,
            Nutrients nutrients,
            Integer healthScore,
            Double confidence,
            List<String> warnings,
            String degradedReason,
            Source source
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Quantity(Double value, String unit) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Nutrients(
            Double kcal,
            Double protein,
            Double fat,
            Double carbs,
            Double fiber,
            Double sugar,
            Double sodium
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String method, String provider) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String taskId, Integer pollAfterSec) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String errorCode, String clientAction, Integer retryAfterSec) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trace(String requestId) {}
}
