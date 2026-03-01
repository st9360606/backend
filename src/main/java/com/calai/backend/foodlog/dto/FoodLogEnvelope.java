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
        List<Hint> hints,
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
            String foodCategory,
            String foodSubCategory,
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

    /**
     * ✅ P2-3：
     * - method：使用者入口（PHOTO / ALBUM / LABEL / BARCODE）
     * - provider：主 provider（GEMINI / OPENFOODFACTS）
     * - resolvedBy：實際解析路徑（AUTO_BARCODE / NAME_SEARCH / OPENFOODFACTS / GEMINI）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(
            String method,
            String provider,
            String resolvedBy
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String taskId, Integer pollAfterSec) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String errorCode, String clientAction, Integer retryAfterSec) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Hint(
            String hintCode,
            String clientAction,
            String message
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trace(String requestId) {}
}
