package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLogListResponse(
        List<Item> items,
        Page page,
        FoodLogEnvelope.Trace trace
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String foodLogId,
            String status,
            String updatedAtUtc,
            String capturedLocalDate,
            String capturedAtUtc,
            String serverReceivedAtUtc,
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
            String degradedReason,
            String foodCategory,
            String foodSubCategory,
            String _reasoning,
            FoodLogEnvelope.LabelMeta labelMeta,
            FoodLogEnvelope.AiMetaView aiMeta
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
