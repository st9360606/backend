package com.calai.backend.workout.dto;

public record TodayWorkoutResponse(
        Integer totalKcalToday,
        java.util.List<WorkoutSessionDto> sessions
) {}
