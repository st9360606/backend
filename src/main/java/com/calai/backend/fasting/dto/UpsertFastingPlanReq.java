package com.calai.backend.fasting.dto;

public record UpsertFastingPlanReq(
        String planCode,   // "16:8" / "14:10" / "20:4" / "22:2" / "12:12" / "18:6"
        String startTime,  // "HH:mm"
        Boolean enabled,
        String timeZone    // IANA TZ, e.g. "Asia/Taipei"
) {}
