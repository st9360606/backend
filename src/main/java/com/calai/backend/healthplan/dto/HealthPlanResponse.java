package com.calai.backend.healthplan.dto;

import java.math.BigDecimal;

public record HealthPlanResponse(
        Long userId,
        String source,
        String calcVersion,
        Integer kcal,
        Integer carbsG,
        Integer proteinG,
        Integer fatG,
        Integer waterMl,
        BigDecimal bmi,
        String bmiClass
) {}
