package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelRefusedResponse(
        String errorCode,          // "MODEL_REFUSED"
        String refuseReason,       // SAFETY / RECITATION / HARM_CATEGORY
        String userMessageKey,     // e.g. PLEASE_PHOTO_FOOD_ONLY
        String hint,              // 可在地化用 key 也行
        List<String> suggestedActions,
        String requestId
) {}