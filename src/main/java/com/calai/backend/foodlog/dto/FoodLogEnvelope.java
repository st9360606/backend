package com.calai.backend.foodlog.dto;

import java.util.UUID;

public record FoodLogEnvelope(
        UUID foodLogId,
        String status,          // PENDING/DRAFT/SAVED/FAILED/DELETED
        String degradeLevel,    // DG-0..DG-4
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
    ) {
    }

    public record Quantity(Double value, String unit) {
    }

    public record Nutrients(Double kcal, Double protein, Double fat, Double carbs, Double fiber, Double sugar,
                            Double sodium) {
    }

    public record Source(String method, String provider) {
    }

    public record Task(UUID taskId, Integer pollAfterSec) {
    }

    public record ApiError(String errorCode, String clientAction, Integer retryAfterSec) {
    }

    public record Trace(String requestId) {
    }
}


