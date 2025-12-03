package com.calai.backend.userprofile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpsertPlanMetricsRequest(
        @NotBlank String unitPreference,     // "KG" / "LBS"
        Integer workoutsPerWeek,             // 0..7 or null
        @NotNull Integer kcal,
        @NotNull Integer carbsG,
        @NotNull Integer proteinG,
        @NotNull Integer fatG,
        @NotNull Integer waterMl,
        @NotNull Double bmi,
        @NotBlank String bmiClass,           // "Normal"/"Obesity"...（你目前用 enum.name 也可）
        @NotBlank String calcVersion
) {}
