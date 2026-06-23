package com.caloshape.backend.users.auto_generate_goals.dto;

import java.util.List;

public record MissingFieldsResponse(
        String code,
        List<String> missingFields,
        String message
) {}
