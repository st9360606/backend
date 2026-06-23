package com.caloshape.backend.workout.dto;

public record TodayWorkoutResponse(
        Integer totalKcalToday,
        java.util.List<WorkoutSessionDto> sessions
) {}
