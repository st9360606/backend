package com.calai.backend.workout.dto;

public record PresetWorkoutDto(
        Long activityId,
        String name,
        Integer kcalPer30Min,
        String iconKey
) {}

