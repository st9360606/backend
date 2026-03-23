package com.calai.backend.foodlog.mapper;

import com.calai.backend.foodlog.model.FoodLogMethod;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 只負責 response/view 層顯示名稱，不寫回 DB。
 */
public final class FoodLogDisplayNameResolver {

    private FoodLogDisplayNameResolver() {}

    public static String resolve(String method, String degradedReason, JsonNode effective) {
        String rawName = textOrNull(effective, "foodName");
        String normalizedReason = normalize(degradedReason);
        FoodLogMethod m = FoodLogMethod.from(method);

        switch (normalizedReason) {
            case "NO_FOOD" -> {
                return "No Food";
            }
            case "NO_LABEL" -> {
                return "No Label";
            }
            case "UNKNOWN_FOOD" -> {
                return "Unknown Food";
            }
        }

        if (rawName != null && !rawName.isBlank()) {
            return rawName;
        }

        if (m == null) {
            return null;
        }

        return switch (m) {
            case LABEL -> "Nutrition Facts Label";
            case PHOTO, ALBUM -> "Unknown Food";
            case BARCODE -> "Unknown Product";
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toUpperCase(Locale.ROOT);
    }
}
