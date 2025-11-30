package com.calai.backend.userprofile.dto;

public record UpsertProfileRequest(
        String gender,
        Integer age,

        // 身高（兩制擇一帶；英制帶齊兩個欄位）
        Double heightCm,
        Short  heightFeet,         // 新增
        Short  heightInches,       // 新增

        // 現在體重（兩制擇一帶）
        Double  weightKg,
        Double  weightLbs,          // ★ Integer -> Double

        String exerciseLevel,
        String goal,

        // 目標體重（兩制擇一帶）
        Double  targetWeightKg,
        Double  targetWeightLbs,    // ★ Integer -> Double
        Integer dailyStepGoal,
        String referralSource,
        String locale
) {}
