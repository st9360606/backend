package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLogOverrideRequest(
        String fieldKey,
        JsonNode newValue,
        String reason
) {}
