package com.calai.backend.users.auto_generate_goals.dto;

public record AutoGoalsRequest(
        Integer workoutsPerWeek,
        Double heightCm,
        Integer heightFeet,
        Integer heightInches,
        Double weightKg,
        Double weightLbs,
        String goalKey
) {}
