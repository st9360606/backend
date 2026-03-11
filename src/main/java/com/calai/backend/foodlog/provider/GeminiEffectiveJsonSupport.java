package com.calai.backend.foodlog.provider;

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


public final class GeminiEffectiveJsonSupport {

    private GeminiEffectiveJsonSupport() {}

    public static ObjectNode normalizeToEffective(JsonNode raw) {
        raw = unwrapRootObjectOrNull(raw);
        if (raw == null || !raw.isObject()) {
            throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        }

        ObjectNode out = JsonNodeFactory.instance.objectNode();

        // foodName
        JsonNode foodNameNode = raw.get("foodName");
        if (foodNameNode != null && !foodNameNode.isNull()) {
            String foodName = foodNameNode.asText(null);
            if (foodName != null && !foodName.isBlank()) {
                out.put("foodName", foodName.trim());
            } else {
                out.putNull("foodName");
            }
        } else {
            out.putNull("foodName");
        }

        // quantity
        ObjectNode oq = out.putObject("quantity");
        JsonNode q = raw.get("quantity");
        if (q != null && q.isObject()) {
            Double value = parseNumberNodeOrText(q.get("value"));
            if (value == null || value <= 0) {
                value = 1d;
            }
            oq.put("value", value);

            String unit = (q.get("unit") == null || q.get("unit").isNull())
                    ? "SERVING"
                    : q.get("unit").asText("SERVING");
            oq.put("unit", normalizeUnit(unit));
        } else {
            oq.put("value", 1d);
            oq.put("unit", QuantityUnit.SERVING.name());
        }

        // nutrients：缺值統一補 0.0
        JsonNode n = raw.get("nutrients");
        if (n == null || !n.isObject()) {
            throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        }

        ObjectNode on = out.putObject("nutrients");
        copyNonNegativeNumberOrZero(n, on, "kcal");
        copyNonNegativeNumberOrZero(n, on, "protein");
        copyNonNegativeNumberOrZero(n, on, "fat");
        copyNonNegativeNumberOrZero(n, on, "carbs");
        copyNonNegativeNumberOrZero(n, on, "fiber");
        copyNonNegativeNumberOrZero(n, on, "sugar");
        copyNonNegativeNumberOrZero(n, on, "sodium");

        // confidence：只保留 Gemini 值
        Double conf = parseNumberNodeOrText(raw.get("confidence"));
        conf = normalizeConfidence(conf);
        if (conf == null) {
            out.putNull("confidence");
        } else {
            out.put("confidence", conf);
        }

        // healthScore：只保留 Gemini 值，不自行計算
        Integer healthScore = parseHealthScoreOrNull(raw.get("healthScore"));
        if (healthScore == null) {
            out.putNull("healthScore");
        } else {
            out.put("healthScore", healthScore);
        }

        // warnings
        ArrayNode outW = JsonNodeFactory.instance.arrayNode();
        JsonNode w = raw.get("warnings");
        if (w != null && w.isArray()) {
            for (JsonNode it : w) {
                if (it == null || it.isNull()) continue;
                FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
                if (ww != null) {
                    outW.add(ww.name());
                }
            }
        }
        out.set("warnings", outW);

        // labelMeta
        ObjectNode outLm = out.putObject("labelMeta");
        JsonNode rawLm = raw.get("labelMeta");

        Double servingsPerContainer = null;
        String basis = null;

        if (rawLm != null && rawLm.isObject()) {
            servingsPerContainer = parseNumberNodeOrText(rawLm.get("servingsPerContainer"));

            JsonNode basisNode = rawLm.get("basis");
            if (basisNode != null && !basisNode.isNull()) {
                basis = normalizeBasisOrNull(basisNode.asText(null));
            }
        }

        if (servingsPerContainer == null || servingsPerContainer <= 0) {
            outLm.putNull("servingsPerContainer");
        } else {
            outLm.put("servingsPerContainer", servingsPerContainer);
        }

        if (basis == null) {
            outLm.putNull("basis");
        } else {
            outLm.put("basis", basis);
        }

        copyCanonicalCategoryAiMetaIfPresent(raw, out);
        return out;
    }

    private static void copyNonNegativeNumberOrZero(JsonNode from, ObjectNode to, String key) {
        JsonNode v = from.get(key);
        if (v == null || v.isMissingNode() || v.isNull()) {
            to.put(key, 0.0);
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
        }

        if (d == null || d < 0) {
            to.put(key, 0.0);
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
            if (isKj && !u.contains("kcal")) d = d / 4.184d;
        } else if ("sodium".equals(key)) {
            if (isGram) d = d * 1000d;
        } else {
            if (isMg) d = d / 1000d;
        }

        to.put(key, d);
    }

    private static Integer parseHealthScoreOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }

        Double d = null;
        if (node.isNumber()) {
            d = node.asDouble();
        } else if (node.isTextual()) {
            try {
                d = Double.parseDouble(node.asText().trim());
            } catch (Exception ignore) {
                return null;
            }
        }

        if (d == null || !Double.isFinite(d)) {
            return null;
        }

        int v = (int) Math.round(d);
        if (v < 0 || v > 10) {
            return null;
        }
        return v;
    }

    public static void finalizeEffective(boolean isLabel, JsonNode rawForFinalize, ObjectNode effective) {
        if (effective == null) {
            return;
        }

        JsonNode raw = (rawForFinalize != null) ? rawForFinalize : effective;

        applyWholePackageScalingIfNeeded(raw, effective);
        roundNutrients1dp(effective);
    }

    public static boolean hasWarning(JsonNode root, String code) {
        root = unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return false;
        JsonNode w = root.get("warnings");
        if (w == null || !w.isArray()) return false;

        for (JsonNode it : w) {
            if (it == null || it.isNull()) continue;
            if (code.equalsIgnoreCase(it.asText())) return true;
        }
        return false;
    }

    public static boolean isAllNutrientsNull(JsonNode root) {
        if (root == null || !root.isObject()) return true;
        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return true;

        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) return false;
        }
        return true;
    }

    public static JsonNode unwrapRootObjectOrNull(JsonNode raw) {
        if (raw == null || raw.isNull()) return null;

        if (raw.isArray()) {
            if (raw.isEmpty()) return null;
            JsonNode first = raw.get(0);
            return (first != null && first.isObject()) ? first : null;
        }

        if (!raw.isObject()) return null;

        JsonNode r = raw.get("result");
        if (r != null && r.isObject()) return r;

        JsonNode d = raw.get("data");
        if (d != null && d.isObject()) return d;

        return raw;
    }

    public static Double parseNumberNodeOrText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asDouble();
        if (!node.isTextual()) return null;

        String raw = node.asText("").trim();
        if (raw.isEmpty()) return null;
        return parseFirstNumber(raw);
    }

    public static String normalizeUnit(String unit) {
        return QuantityUnit.fromRawOrDefault(unit).name();
    }

    static double round1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    public static void roundNutrients1dp(ObjectNode effective) {
        if (effective == null) return;

        JsonNode nNode = effective.get("nutrients");
        if (nNode == null || !nNode.isObject()) return;

        ObjectNode n = (ObjectNode) nNode;
        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            JsonNode v = n.get(k);
            if (v == null || v.isNull()) continue;

            if (v.isNumber()) {
                double d = v.asDouble();
                if (Math.abs(d) < 0.05d) {
                    d = 0.0d;
                }
                n.put(k, round1(d));
            } else if (v.isTextual()) {
                String s = v.asText("").trim();
                if (!s.isEmpty()) {
                    try {
                        double d = Double.parseDouble(s.replace(',', '.').replaceAll("[^0-9.\\-]", ""));
                        n.put(k, round1(d));
                    } catch (Exception ignore) {
                        // keep original
                    }
                }
            }
        }
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
            if (isKj && !u.contains("kcal")) d = d / 4.184d;
        } else if ("sodium".equals(key)) {
            if (isGram) d = d * 1000d;
        } else {
            if (isMg) d = d / 1000d;
        }

        to.put(key, d);
    }

    private static Double normalizeConfidence(Double v) {
        if (v == null) return null;
        if (v >= 0.0 && v <= 1.0) return v;
        if (v > 1.0 && v <= 100.0) return v / 100.0;
        return null;
    }

    private static void copyCanonicalCategoryAiMetaIfPresent(JsonNode raw, ObjectNode out) {
        if (raw == null || !raw.isObject() || out == null) return;

        JsonNode aiMeta = raw.get("aiMeta");
        if (aiMeta == null || !aiMeta.isObject()) return;

        String foodCategory = textOrNull(aiMeta, "foodCategory");
        String foodSubCategory = textOrNull(aiMeta, "foodSubCategory");

        if (foodCategory == null && foodSubCategory == null) return;

        ObjectNode outAiMeta;
        JsonNode existing = out.get("aiMeta");
        if (existing instanceof ObjectNode obj) {
            outAiMeta = obj;
        } else {
            outAiMeta = out.putObject("aiMeta");
        }

        if (foodCategory != null) {
            outAiMeta.put("foodCategory", foodCategory);
        }
        if (foodSubCategory != null) {
            outAiMeta.put("foodSubCategory", foodSubCategory);
        }
    }

    /**
     * whole-package canonicalization：
     * 1. WHOLE_PACKAGE：保留 logical unit（PACK/BOTTLE/CAN/PIECE/SERVING）
     * 2. PER_SERVING + servings>1：乘成整包，basis 改 WHOLE_PACKAGE
     * 3. PER_SERVING + servings<=1：保留 PER_SERVING
     * 4. ESTIMATED_PORTION：保留原語意
     */
    private static void applyWholePackageScalingIfNeeded(JsonNode raw, ObjectNode effective) {
        if (effective == null) return;

        Double servings = null;
        NutritionBasis basis = null;

        JsonNode lm = (raw == null) ? null : raw.get("labelMeta");
        if (lm != null && lm.isObject()) {
            servings = parseNumberNodeOrText(lm.get("servingsPerContainer"));
            basis = NutritionBasis.fromRawOrNull(textOrNull(lm, "basis"));
        }

        ObjectNode outLm = effective.with("labelMeta");
        ObjectNode q = effective.with("quantity");
        JsonNode nNode = effective.get("nutrients");
        ObjectNode n = (nNode instanceof ObjectNode obj) ? obj : null;

        // 保留 prompt logical unit，不再強制改成 SERVING
        QuantityUnit logicalUnit = QuantityUnit.fromRawOrDefault(textOrNull(q, "unit"));
        q.put("value", 1.0);
        q.put("unit", logicalUnit.name());

        if (basis == null) {
            return;
        }

        if (basis == NutritionBasis.WHOLE_PACKAGE) {
            if (servings == null || servings <= 0) {
                outLm.putNull("servingsPerContainer");
            } else {
                outLm.put("servingsPerContainer", servings);
            }
            outLm.put("basis", NutritionBasis.WHOLE_PACKAGE.name());
            return;
        }

        if (basis == NutritionBasis.PER_SERVING) {
            if (servings != null && servings > 1.0 && n != null) {
                for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
                    JsonNode v = n.get(k);
                    if (v != null && v.isNumber()) {
                        n.put(k, v.asDouble() * servings);
                    }
                }
                outLm.put("servingsPerContainer", servings);
                outLm.put("basis", NutritionBasis.WHOLE_PACKAGE.name());
                return;
            }

            if (servings == null || servings <= 0) {
                outLm.putNull("servingsPerContainer");
            } else {
                outLm.put("servingsPerContainer", servings);
            }
            outLm.put("basis", NutritionBasis.PER_SERVING.name());
            return;
        }

        if (basis == NutritionBasis.ESTIMATED_PORTION) {
            if (servings == null || servings <= 0) {
                outLm.putNull("servingsPerContainer");
            } else {
                outLm.put("servingsPerContainer", servings);
            }
            outLm.put("basis", NutritionBasis.ESTIMATED_PORTION.name());
        }
    }

    private static String normalizeBasisOrNull(String basis) {
        NutritionBasis v = NutritionBasis.fromRawOrNull(basis);
        return v == null ? null : v.name();
    }

    private static Double parseFirstNumber(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?\\d+(?:[.,]\\d+)?)").matcher(raw);
        if (!m.find()) return null;
        String s = m.group(1).replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
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
