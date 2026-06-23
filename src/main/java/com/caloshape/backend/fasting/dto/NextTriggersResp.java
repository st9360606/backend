package com.caloshape.backend.fasting.dto;

public record NextTriggersResp(
        String nextStartUtc, // ISO-8601 Instant string
        String nextEndUtc
) {}
