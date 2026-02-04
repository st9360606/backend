package com.calai.backend.foodlog.task;

import java.util.Locale;

public enum FoodLogWarning {
    NON_FOOD_SUSPECT,
    LABEL_PARTIAL,
    NO_FOOD_DETECTED,
    NO_LABEL_DETECTED,// ✅ NEW：Label 專用（不會誤傷 no-food 規則）
    SERVING_SIZE_UNKNOWN,
    LOW_CONFIDENCE,
    MIXED_MEAL,
    BLURRY_IMAGE,
    UNKNOWN_FOOD,
    QUANTITY_OUTLIER,
    KCAL_OUTLIER,
    MACRO_OUTLIER,
    UNIT_UNKNOWN;

    public static FoodLogWarning parseOrNull(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        try { return FoodLogWarning.valueOf(v); }
        catch (Exception ignored) { return null; }
    }
}
