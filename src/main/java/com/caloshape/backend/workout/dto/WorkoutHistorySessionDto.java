package com.caloshape.backend.workout.dto;

import java.time.LocalDate;

public record WorkoutHistorySessionDto(
        Long id,
        String name,
        Integer minutes,
        Integer kcal,
        LocalDate localDate,
        String dateLabel,
        String timeLabel
) {}
