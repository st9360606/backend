package com.calai.backend.workout.dto;

public record EstimateResponse(
        String status,           // "ok" or "not_found"
        Long activityId,
        String activityDisplay,  // 原文名稱
        Integer minutes,
        Integer kcal
) {}
