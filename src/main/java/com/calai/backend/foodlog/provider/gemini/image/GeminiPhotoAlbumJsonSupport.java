package com.calai.backend.foodlog.provider.gemini.image;

import com.calai.backend.foodlog.provider.gemini.support.GeminiEffectiveJsonSupport;
import com.calai.backend.foodlog.unit.NutritionBasis;
import com.calai.backend.foodlog.unit.QuantityUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PHOTO / ALBUM 專用 normalize。
 * 重點：
 * 1. 對齊 GeminiPromptFactory
 * 2. 保留 PACK / BOTTLE
 * 3. 保留 ESTIMATED_PORTION
 * 4. 不做 whole-package scaling，不把 PACK/BOTTLE 改回 SERVING
 */
public final class GeminiPhotoAlbumJsonSupport {

    private GeminiPhotoAlbumJsonSupport() {
    }

    public static ObjectNode normalizeToEffective(JsonNode raw) {
        return GeminiEffectiveJsonSupport.normalizeToEffective(raw);
    }

    public static void finalizeEffective(ObjectNode effective) {
        if (effective == null) {
            return;
        }

        roundNutrients1dp(effective);

        ObjectNode q = effective.with("quantity");
        Double value = parseNumberNodeOrText(q.get("value"));
        if (value == null || value <= 0) {
            q.put("value", 1.0);
        }

        QuantityUnit unit = QuantityUnit.fromRawOrDefault(textOrNull(q, "unit"));
        q.put("unit", unit.name());

        // ✅ confidence 只保留 Gemini 值，不自行補預設分數
        Double confidence = normalizeConfidence(parseNumberNodeOrText(effective.get("confidence")));
        if (confidence == null) {
            effective.putNull("confidence");
        } else {
            effective.put("confidence", confidence);
        }

        if (!effective.has("warnings") || !effective.get("warnings").isArray()) {
            effective.putArray("warnings");
        }

        ObjectNode labelMeta = effective.with("labelMeta");

        Double servingsPerContainer = parseNumberNodeOrText(labelMeta.get("servingsPerContainer"));
        if (servingsPerContainer == null || servingsPerContainer <= 0) {
            labelMeta.putNull("servingsPerContainer");
        } else {
            labelMeta.put("servingsPerContainer", servingsPerContainer);
        }

        NutritionBasis basis = NutritionBasis.fromRawOrNull(textOrNull(labelMeta, "basis"));
        if (basis == null) {
            labelMeta.putNull("basis");
        } else {
            labelMeta.put("basis", basis.name());
        }
    }

    public static boolean isWholeContainerLike(JsonNode root) {
        root = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) {
            return false;
        }

        JsonNode q = root.path("quantity");
        QuantityUnit unit = QuantityUnit.fromRawOrDefault(textOrNull(q, "unit"));

        JsonNode labelMeta = root.path("labelMeta");
        NutritionBasis basis = NutritionBasis.fromRawOrNull(textOrNull(labelMeta, "basis"));
        Double servingsPerContainer = parseNumberNodeOrText(labelMeta.get("servingsPerContainer"));

        return unit == QuantityUnit.PACK
               || unit == QuantityUnit.BOTTLE
               || unit == QuantityUnit.CAN
               || basis == NutritionBasis.WHOLE_PACKAGE
               || basis == NutritionBasis.PER_SERVING
               || (servingsPerContainer != null && servingsPerContainer > 0);
    }

    public static boolean allCoreQuartetZero(JsonNode root) {
        root = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return true;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return true;

        return isZeroOrNull(n.get("kcal"))
               && isZeroOrNull(n.get("protein"))
               && isZeroOrNull(n.get("fat"))
               && isZeroOrNull(n.get("carbs"));
    }

    private static boolean isZeroOrNull(JsonNode node) {
        Double v = parseNumberNodeOrText(node);
        return v == null || Math.abs(v) < 0.0001d;
    }

    private static void roundNutrients1dp(ObjectNode effective) {
        JsonNode nNode = effective.get("nutrients");
        if (!(nNode instanceof ObjectNode nutrients)) {
            return;
        }

        for (String key : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            JsonNode v = nutrients.get(key);
            if (v == null || v.isNull()) continue;

            if (v.isNumber()) {
                double d = v.asDouble();
                if (Math.abs(d) < 0.05d) {
                    d = 0.0d;
                }
                nutrients.put(key, round1(d));
            }
        }
    }

    private static double round1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double normalizeConfidence(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        // ✅ 嚴格模式：只接受 0.0 ~ 1.0
        return (value >= 0.0 && value <= 1.0) ? value : null;
    }

    private static Double parseNumberNodeOrText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asDouble();
        if (!node.isTextual()) return null;

        String raw = node.asText("").trim();
        if (raw.isEmpty()) return null;
        return parseFirstNumber(raw);
    }

    private static Double parseFirstNumber(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?\\d+(?:[.,]\\d+)?)").matcher(raw);
        if (!m.find()) return null;

        String s = m.group(1).replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
