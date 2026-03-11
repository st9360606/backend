package com.calai.backend.foodlog.unit;

import java.util.List;
import java.util.Locale;

/**
 * 統一管理 nutrition basis。
 * 對齊 GeminiPromptFactory PHOTO / ALBUM 規格：
 * - WHOLE_PACKAGE
 * - PER_SERVING
 * - ESTIMATED_PORTION
 */
public enum NutritionBasis {
    WHOLE_PACKAGE,
    PER_SERVING,
    ESTIMATED_PORTION;

    public static NutritionBasis fromRawOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String v = raw.trim().toUpperCase(Locale.ROOT);

        if (v.contains("WHOLE_PACKAGE")) {
            return WHOLE_PACKAGE;
        }
        if (v.contains("PER_SERVING")) {
            return PER_SERVING;
        }
        if (v.contains("ESTIMATED_PORTION")) {
            return ESTIMATED_PORTION;
        }
        return null;
    }

    public static List<String> promptValues() {
        return List.of("WHOLE_PACKAGE", "PER_SERVING", "ESTIMATED_PORTION");
    }
}
