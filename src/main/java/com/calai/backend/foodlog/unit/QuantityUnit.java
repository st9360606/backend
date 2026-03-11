package com.calai.backend.foodlog.unit;

import java.util.List;
import java.util.Locale;

/**
 * 統一管理 nutrition quantity unit。
 * 限制為邏輯單位，不使用 g/ml/oz 等物理單位。
 */
public enum QuantityUnit {
    SERVING,
    PACK,
    BOTTLE,
    CAN,
    PIECE;

    public static QuantityUnit fromRawOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return SERVING;
        }

        String v = raw.trim().toUpperCase(Locale.ROOT);

        return switch (v) {
            case "PACK", "PACKAGE", "BAG" -> PACK;
            case "BOTTLE" -> BOTTLE;
            case "CAN", "TIN" -> CAN;
            case "PIECE", "PC", "PCS", "UNIT", "WHOLE" -> PIECE;
            case "SERVING", "SERVINGS", "PORTION", "PORTIONS", "DISH" -> SERVING;
            default -> SERVING;
        };
    }

    public static List<String> promptValues() {
        return List.of("SERVING", "PACK", "BOTTLE", "CAN", "PIECE");
    }
}
