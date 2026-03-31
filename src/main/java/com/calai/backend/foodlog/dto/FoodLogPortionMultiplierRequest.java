package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLogPortionMultiplierRequest(
        Integer multiplier,
        String reason
) {}
