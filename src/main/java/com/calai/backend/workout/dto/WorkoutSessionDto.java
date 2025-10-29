package com.calai.backend.workout.dto;

public record WorkoutSessionDto(
        Long id,
        String name,
        Integer minutes,
        Integer kcal,
        String timeLabel
) {}
