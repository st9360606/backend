package com.calai.backend.foodlog.unit;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Food log pipeline 中允許保留下來的 warning codes。
 *
 * 注意：
 * 1. Provider / OFF / fallback 若有新增 warning code，必須同步加到這裡。
 * 2. GeminiEffectiveJsonSupport.normalizeToEffective() 會透過 parseOrNull() 過濾 warning，
 *    不在 enum 裡的值會被直接丟棄。
 */
public enum FoodLogWarning {

    // 通用
    LOW_CONFIDENCE,
    UNKNOWN_FOOD,
    NON_FOOD_SUSPECT,
    NO_FOOD_DETECTED,
    BLURRY_IMAGE,

    // Label / OCR
    LABEL_PARTIAL,
    NO_LABEL_DETECTED,
    SERVING_SIZE_UNKNOWN,
    UNIT_UNKNOWN,

    // Nutrition sanity / quantity
    QUANTITY_OUTLIER,
    KCAL_OUTLIER,
    MACRO_OUTLIER,

    // Meal shape
    MIXED_MEAL,

    // ✅ 新增：已辨識到具體包裝商品，但未能取得可靠營養標示
    MISSING_NUTRITION_FACTS,

    // ✅ 新增：OFF name-search 命中，但包裝尺寸未被可靠驗證
    PACKAGE_SIZE_UNVERIFIED;

    public static FoodLogWarning parseOrNull(String s) {
        if (s == null) return null;

        String v = s.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return null;

        try {
            return FoodLogWarning.valueOf(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String promptCsv() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
