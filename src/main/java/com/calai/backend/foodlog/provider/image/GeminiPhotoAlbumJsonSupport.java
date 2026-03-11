package com.calai.backend.foodlog.provider.image;

import com.calai.backend.foodlog.provider.GeminiEffectiveJsonSupport;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.calai.backend.foodlog.unit.NutritionBasis;
import com.calai.backend.foodlog.unit.QuantityUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * PHOTO / ALBUM 專用 normalize。
 *
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

    public static boolean hasCompleteCoreQuartet(JsonNode root) {
        root = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return false;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return false;

        return hasValue(n.get("kcal"))
               && hasValue(n.get("protein"))
               && hasValue(n.get("fat"))
               && hasValue(n.get("carbs"));
    }

    public static boolean hasAnyCoreNutrientValue(JsonNode root) {
        root = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return false;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return false;

        return hasValue(n.get("kcal"))
               || hasValue(n.get("protein"))
               || hasValue(n.get("fat"))
               || hasValue(n.get("carbs"));
    }

    private static boolean hasValue(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static void copyNonNegativeNumberOrNull(JsonNode from, ObjectNode to, String key) {
        JsonNode v = from.get(key);
        if (v == null || v.isMissingNode() || v.isNull()) {
            to.putNull(key);
            return;
        }

        Double d = null;
        String unit = null;

        if (v.isNumber()) {
            d = v.asDouble();
        } else if (v.isTextual()) {
            String raw = v.asText("").trim();
            if (!raw.isEmpty()) {
                d = parseFirstNumber(raw);
                unit = raw;
            }
        } else if (v.isObject()) {
            JsonNode vv = v.get("value");
            if (vv != null) {
                if (vv.isNumber()) d = vv.asDouble();
                else if (vv.isTextual()) d = parseFirstNumber(vv.asText("").trim());
            }
            JsonNode uu = v.get("unit");
            if (uu == null) uu = v.get("uom");
            if (uu != null && uu.isTextual()) unit = uu.asText();
        } else {
            to.putNull(key);
            return;
        }

        if (d == null || d < 0) {
            to.putNull(key);
            return;
        }

        String u = (unit == null) ? "" : unit.toLowerCase(Locale.ROOT).trim();
        boolean isMg = u.contains("mg") || u.contains("毫克");
        boolean isGram = !isMg && (
                u.equals("g")
                || u.endsWith(" g")
                || u.endsWith("g")
                || u.contains("公克")
                || u.equals("克")
        );
        boolean isKj = u.contains("kj") || u.contains("千焦");

        if ("kcal".equals(key)) {
            if (isKj && !u.contains("kcal")) {
                d = d / 4.184d;
            }
        } else if ("sodium".equals(key)) {
            if (isGram) {
                d = d * 1000d;
            }
        } else {
            if (isMg) {
                d = d / 1000d;
            }
        }

        to.put(key, d);
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
        if (value == null) return null;
        if (value >= 0.0 && value <= 1.0) return value;
        if (value > 1.0 && value <= 100.0) return value / 100.0;
        return null;
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
