package com.calai.backend.weight.dto;

import java.math.BigDecimal;
import java.util.List;

public record SummaryDto(
        BigDecimal goalKg,
        Integer goalLbs,
        BigDecimal currentKg,
        Integer currentLbs,
        BigDecimal firstWeightKgAllTimeKg,
        Double achievedPercent,
        List<WeightItemDto> series
) {}

