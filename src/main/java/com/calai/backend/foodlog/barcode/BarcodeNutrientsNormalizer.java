package com.calai.backend.foodlog.barcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * BARCODE 專用 nutrients 正規化：
 * - 寫入 effective 前：null / 缺值 / 空字串 / 非數字 => 0.0
 * - 讀取 response 時：可選擇 zeroIfMissing=true 時回 0.0
 *
 * 注意：只給 BARCODE flow 使用，避免影響 PHOTO / ALBUM / LABEL。
 */
public final class BarcodeNutrientsNormalizer {

    private BarcodeNutrientsNormalizer() {}

    private static final List<String> NUTRIENT_FIELDS = List.of(
            "kcal",
            "protein",
            "fat",
            "carbs",
            "fiber",
            "sugar",
            "sodium"
    );

    /**
     * 寫入 DB 前清洗 BARCODE effective。
     */
    public static void fillMissingWithZero(ObjectNode effective) {
        if (effective == null) return;

        ObjectNode nutrients = ensureObject(effective, "nutrients");

        for (String field : NUTRIENT_FIELDS) {
            JsonNode v = nutrients.get(field);

            if (v == null || v.isNull()) {
                nutrients.put(field, 0.0);
                continue;
            }

            if (v.isNumber()) {
                double d = v.asDouble();
                if (!Double.isFinite(d)) {
                    nutrients.put(field, 0.0);
                }
                continue;
            }

            if (v.isTextual()) {
                String raw = v.asText("");
                if (raw == null || raw.isBlank()) {
                    nutrients.put(field, 0.0);
                    continue;
                }
                try {
                    double d = Double.parseDouble(raw.trim());
                    nutrients.put(field, Double.isFinite(d) ? d : 0.0);
                } catch (NumberFormatException ignore) {
                    nutrients.put(field, 0.0);
                }
                continue;
            }

            // 其他奇怪型別：保守補 0.0
            nutrients.put(field, 0.0);
        }
    }

    /**
     * 讀取 response 時使用：
     * - zeroIfMissing=true  -> 缺值/空值/非數字回 0.0
     * - zeroIfMissing=false -> 維持原本 null
     */
    public static Double readNumber(JsonNode nutrients, String field, boolean zeroIfMissing) {
        if (nutrients == null || nutrients.isNull()) {
            return zeroIfMissing ? 0.0 : null;
        }

        JsonNode v = nutrients.get(field);
        if (v == null || v.isNull()) {
            return zeroIfMissing ? 0.0 : null;
        }

        if (v.isNumber()) {
            double d = v.asDouble();
            if (Double.isFinite(d)) {
                return d;
            }
            return zeroIfMissing ? 0.0 : null;
        }

        if (v.isTextual()) {
            String raw = v.asText("");
            if (raw.isBlank()) {
                return zeroIfMissing ? 0.0 : null;
            }
            try {
                double d = Double.parseDouble(raw.trim());
                if (Double.isFinite(d)) {
                    return d;
                }
                return zeroIfMissing ? 0.0 : null;
            } catch (NumberFormatException ignore) {
                return zeroIfMissing ? 0.0 : null;
            }
        }

        return zeroIfMissing ? 0.0 : null;
    }

    private static ObjectNode ensureObject(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        if (node != null && node.isObject()) {
            return (ObjectNode) node;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        root.set(field, out);
        return out;
    }
}
