package com.calai.backend.userprofile.dto;

import java.time.Instant;

public record UserProfileDto(
        String gender,
        Integer age,
        Double heightCm,
        Short heightFeet,
        Short heightInches,
        Double weightKg,
        Double weightLbs,
        String exerciseLevel,
        String goal,
        Double goalWeightKg,
        Double goalWeightLbs,
        Integer dailyStepGoal,
        String referralSource,
        String locale,
        String timezone,
        Instant createdAt,
        Instant updatedAt
) {}
