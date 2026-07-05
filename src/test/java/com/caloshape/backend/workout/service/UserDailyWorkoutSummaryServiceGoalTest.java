package com.caloshape.backend.workout.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserDailyWorkoutSummaryServiceGoalTest {

    @Test
    void should_combine_workout_goal_with_estimated_step_goal_kcal() {
        assertEquals(
                800,
                UserDailyWorkoutSummaryService.calculateTotalActivityGoalKcal(
                        450,
                        10_000,
                        70d
                )
        );
    }

    @Test
    void should_keep_workout_goal_when_step_goal_cannot_be_estimated() {
        assertEquals(
                450,
                UserDailyWorkoutSummaryService.calculateTotalActivityGoalKcal(
                        450,
                        10_000,
                        null
                )
        );
        assertEquals(
                450,
                UserDailyWorkoutSummaryService.calculateTotalActivityGoalKcal(
                        450,
                        0,
                        70d
                )
        );
    }

    @Test
    void should_use_safe_defaults_and_non_negative_goals() {
        assertEquals(
                450,
                UserDailyWorkoutSummaryService.calculateTotalActivityGoalKcal(
                        null,
                        null,
                        null
                )
        );
        assertEquals(
                350,
                UserDailyWorkoutSummaryService.calculateTotalActivityGoalKcal(
                        -10,
                        10_000,
                        70d
                )
        );
    }
}
