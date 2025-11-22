package com.calai.backend.weight.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WeightItemDto(
        LocalDate logDate,
        BigDecimal weightKg,
        BigDecimal weightLbs,   // ★ 原本 Integer → BigDecimal
        String photoUrl
) {}
