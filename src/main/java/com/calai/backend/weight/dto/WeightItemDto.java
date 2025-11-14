package com.calai.backend.weight.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WeightItemDto(
        LocalDate logDate,
        BigDecimal weightKg,
        Integer weightLbs, // 方便前端直接顯示整數
        String photoUrl
) {}

