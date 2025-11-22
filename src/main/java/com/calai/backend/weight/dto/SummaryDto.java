package com.calai.backend.weight.dto;

import java.math.BigDecimal;
import java.util.List;

public record SummaryDto(
        BigDecimal goalKg,
        BigDecimal goalLbs,              // ★ 原本是 Integer，改成 BigDecimal
        BigDecimal currentKg,
        BigDecimal currentLbs,           // ★ 原本是 Integer，改成 BigDecimal
        BigDecimal firstWeightKgAllTimeKg,
        // ★ 新增：來自 user_profiles 的原始體重（current profile weight）
        BigDecimal profileWeightKg,
        BigDecimal profileWeightLbs,
        Double achievedPercent,
        List<WeightItemDto> series
) {}
