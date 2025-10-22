package com.calai.backend.fasting.dto;

public record FastingPlanDto(
        String planCode,   // e.g. "16:8"
        String startTime,  // "HH:mm"
        String endTime,    // "HH:mm"
        boolean enabled,
        String timeZone
) {}
