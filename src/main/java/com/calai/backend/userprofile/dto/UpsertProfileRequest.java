package com.calai.backend.userprofile.dto;

public record UpsertProfileRequest(
        String gender, Integer age, Double heightCm, Double weightKg,
        String exerciseLevel, String goal, Double targetWeightKg,
        String referralSource, String locale
) {}
