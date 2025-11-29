package com.calai.backend.healthplan.dto;

import java.math.BigDecimal;

public record SaveHealthPlanRequest(
        // meta
        String source,          // "ONBOARDING"
        String calcVersion,     // "healthcalc_v1"

        // inputs snapshot
        String goalKey,
        String gender,
        Integer age,
        BigDecimal heightCm,
        BigDecimal weightKg,
        BigDecimal targetWeightKg,
        String unitPreference,  // "KG" / "LBS"
        Integer workoutsPerWeek,

        // results
        Integer kcal,
        Integer carbsG,
        Integer proteinG,
        Integer fatG,
        Integer waterMl,
        BigDecimal bmi,
        String bmiClass
) {}