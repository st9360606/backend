package com.calai.backend.users.profile.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record NutritionGoalsManualRequest(
        @NotNull(message = "Calories is required") @Min(value = 1, message = "Calories must be greater than 0") Integer kcal,
        @NotNull(message = "Protein is required")  @Min(value = 1, message = "Protein must be greater than 0") Integer proteinG,
        @NotNull(message = "Carbs is required")    @Min(value = 1, message = "Carbs must be greater than 0") Integer carbsG,
        @NotNull(message = "Fat is required")      @Min(value = 1, message = "Fat must be greater than 0") Integer fatG,
        @NotNull(message = "Fiber is required")    @Min(value = 1, message = "Fiber must be greater than 0") Integer fiberG,
        @NotNull(message = "Sugar is required")    @Min(value = 1, message = "Sugar must be greater than 0") Integer sugarG,
        @NotNull(message = "Sodium is required")   @Min(value = 1, message = "Sodium must be greater than 0") Integer sodiumMg
) {}
