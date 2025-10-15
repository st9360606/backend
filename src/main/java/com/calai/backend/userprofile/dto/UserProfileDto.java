package com.calai.backend.userprofile.dto;

public record UserProfileDto(
        String gender,
        Integer age,
        Double heightCm,
        Short heightFeet,          // 新增
        Short heightInches,        // 新增
        Double weightKg,
        Integer weightLbs,         // 新增
        String exerciseLevel,
        String goal,
        Double targetWeightKg,
        Integer targetWeightLbs,   // 新增
        String referralSource,
        String locale
) {}
