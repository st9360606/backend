package com.calai.backend.userprofile.dto;

/**
 * 更新目標體重的簡化請求：
 * - value: 使用者輸入的數字
 * - unit : "KG" 或 "LBS"
 */
public record UpdateTargetWeightRequest(
        Double value,   // 使用者輸入的數值
        String unit     // "KG" or "LBS"
) {}