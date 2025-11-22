package com.calai.backend.weight.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LogWeightRequest(
        /** 若前端送 lbs 也沒關係，kg & lbs 任一必填，缺一個由後端換算 */
        BigDecimal weightKg,
        BigDecimal weightLbs,
        /** 可回填過去日期（由 UI 底部日期選擇器） */
        LocalDate logDate
) {}
