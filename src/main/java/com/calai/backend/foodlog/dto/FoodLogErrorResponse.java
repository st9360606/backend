package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// BEGIN CHANGE: support errorCode + legacy code alias
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLogErrorResponse(
        String errorCode,          // ✅ Android 解析用
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
    // ✅ 舊相容：仍輸出 code 欄位（值=errorCode）
    @JsonProperty("code")
    public String code() {
        return errorCode;
    }

    public FoodLogErrorResponse(
            String errorCode,
            String message,
            String requestId,
            String clientAction,
            Integer retryAfterSec
    ) {
        this(errorCode, message, requestId, clientAction, retryAfterSec,
                null, null, null, null, null);
    }
}
// END CHANGE
