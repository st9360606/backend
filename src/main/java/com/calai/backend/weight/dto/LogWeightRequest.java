package com.calai.backend.weight.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LogWeightRequest(
        /** 若前端傳 lbs，請先轉為 kg 後送來；此欄必填且為 kg */
        BigDecimal weightKg,
        /** 可回填過去日期（由 UI 底部日期選擇器） */
        LocalDate logDate
) {}
