package com.calai.backend.workout.dto;

public record LogWorkoutRequest(
        Long activityId,
        Integer minutes,
        Integer kcal // optional; server 會重算最終 kcal
) {}