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
        Integer weightLbs,         // 新增

        String exerciseLevel,
        String goal,

        // 目標體重（兩制擇一帶）
        Double  targetWeightKg,
        Integer targetWeightLbs,   // 新增

        String referralSource,
        String locale
) {}
