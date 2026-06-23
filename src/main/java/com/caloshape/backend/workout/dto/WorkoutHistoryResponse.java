package com.caloshape.backend.workout.dto;

import java.util.List;

public record WorkoutHistoryResponse(
        Integer totalKcal,
        List<WorkoutHistorySessionDto> sessions
) {}
