package com.calai.backend.userprofile.dto;

public record UserProfileDto(
        String gender,
        Integer age,
        Double heightCm,
        Short heightFeet,          // 新增
        Short heightInches,        // 新增
        Double weightKg,
        Double  weightLbs,          // ★ Integer -> Double
        String exerciseLevel,
        String goal,
        Double targetWeightKg,
        Double  targetWeightLbs,    // ★ Integer -> Double
        String referralSource,
        String locale
) {}
