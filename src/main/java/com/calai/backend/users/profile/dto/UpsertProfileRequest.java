package com.calai.backend.users.profile.dto;

public record UpsertProfileRequest(
        String gender,
        Integer age,

        // 身高（兩制擇一帶；英制帶齊兩個欄位）
        Double heightCm,
        Short  heightFeet,
        Short  heightInches,

        // 現在體重（兩制擇一帶）
        Double  weightKg,
        Double  weightLbs,

        String exerciseLevel,
        String goal,

        // 目標體重（兩制擇一帶）
        Double  goalWeightKg,
        Double  goalWeightLbs,
        Integer dailyStepGoal,
        String referralSource,
        String locale,

        String unitPreference,
        Integer workoutsPerWeek,
        Integer waterMl
) {}
