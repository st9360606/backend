package com.calai.backend.weight.dto;

import java.math.BigDecimal;
import java.util.List;

public record SummaryDto(
        BigDecimal goalKg,
        Integer goalLbs,
        BigDecimal currentKg,
        Integer currentLbs,
        BigDecimal firstWeightKgAllTimeKg,   // ★ 新增：全時段起始體重（kg）
        double achievedPercent,
        List<WeightItemDto> series
) {}

