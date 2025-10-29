package com.calai.backend.workout.dto;

public record LogWorkoutResponse(
        WorkoutSessionDto savedSession,
        TodayWorkoutResponse today // 更新後的今日狀態
) {}