package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLogErrorResponse(
        String code,
        String message,
        String requestId,
        String clientAction,
        Integer retryAfterSec,
        String nextAllowedAtUtc,
        Integer cooldownSeconds,
        Integer cooldownLevel,
        String cooldownReason,
        String suggestedTier
) {
    public FoodLogErrorResponse(
            String code,
            String message,
            String requestId,
            String clientAction,
            Integer retryAfterSec
    ) {
        this(code, message, requestId, clientAction, retryAfterSec,
                null, null, null, null, null);
    }
}


