package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.task.FoodLogWarning;
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

        JsonNode foodNameNode = raw.get("foodName");
        if (foodNameNode != null && !foodNameNode.isNull()) {
            String foodName = foodNameNode.asText(null);
            if (foodName != null && !foodName.isBlank()) {
                out.put("foodName", foodName);
            }
        }

        ObjectNode oq = out.putObject("quantity");
        JsonNode q = raw.get("quantity");
        if (q != null && q.isObject()) {
            Double value = parseNumberNodeOrText(q.get("value"));
            if (value == null || value < 0) value = 1d;
            oq.put("value", value);

            String unit = (q.get("unit") == null || q.get("unit").isNull())
                    ? "SERVING"
                    : q.get("unit").asText("SERVING");
            oq.put("unit", normalizeUnit(unit));
        } else {
            oq.put("value", 1d);
            oq.put("unit", "SERVING");
        }

        JsonNode n = raw.get("nutrients");
        if (n == null || !n.isObject()) {
            throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        }

        ObjectNode on = out.putObject("nutrients");
        copyNonNegativeNumberOrNull(n, on, "kcal");
        copyNonNegativeNumberOrNull(n, on, "protein");
        copyNonNegativeNumberOrNull(n, on, "fat");
        copyNonNegativeNumberOrNull(n, on, "carbs");
        copyNonNegativeNumberOrNull(n, on, "fiber");
        copyNonNegativeNumberOrNull(n, on, "sugar");
        copyNonNegativeNumberOrNull(n, on, "sodium");

        Double conf = parseNumberNodeOrText(raw.get("confidence"));
        conf = normalizeConfidence(conf);
        if (conf == null) out.putNull("confidence");
        else out.put("confidence", conf);

        ArrayNode outW = JsonNodeFactory.instance.arrayNode();
        JsonNode w = raw.get("warnings");
        if (w != null && w.isArray()) {
            for (JsonNode it : w) {
                if (it == null || it.isNull()) continue;
                FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
                if (ww != null) outW.add(ww.name());
            }
        }
        out.set("warnings", outW);

        if (raw.has("labelMeta")) {
            out.set("labelMeta", raw.get("labelMeta"));
        }

        copyCanonicalCategoryAiMetaIfPresent(raw, out);
        return out;
    }

    public static void finalizeEffective(boolean isLabel, JsonNode rawForFinalize, ObjectNode effective) {
        if (effective == null) return;

        JsonNode raw = (rawForFinalize != null) ? rawForFinalize : effective;

        if (isLabel) {
            applyWholePackageScalingIfNeeded(raw, effective);
        }

        roundNutrients1dp(effective);
    }

    public static boolean hasAnyNutrientValue(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        return root != null && !isAllNutrientsNull(root);
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
        if (unit == null) return "SERVING";
        String u = unit.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return "SERVING";

        if (u.equals("G") || u.equals("GRAM") || u.equals("GRAMS")) return "GRAM";
        if (u.equals("ML")
            || u.equals("MILLILITER")
            || u.equals("MILLILITERS")
            || u.equals("MILLILITRE")
            || u.equals("MILLILITRES")) {
            return "ML";
        }
        if (u.equals("SERVING")
            || u.equals("SERVINGS")
            || u.equals("PORTION")
            || u.equals("PORTIONS")
            || u.equals("PCS")
            || u.equals("PC")) {
            return "SERVING";
        }
        if (u.equals("GRAM") || u.equals("ML") || u.equals("SERVING")) return u;
        return "SERVING";
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
                n.put(k, round1(v.asDouble()));
            } else if (v.isTextual()) {
                String s = v.asText("").trim();
                if (!s.isEmpty()) {
                    try {
                        double d = Double.parseDouble(s.replace(',', '.').replaceAll("[^0-9.\\-]", ""));
                        n.put(k, round1(d));
                    } catch (Exception ignore) {
                        // keep original text
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

    private static void applyWholePackageScalingIfNeeded(JsonNode raw, ObjectNode effective) {
        if (raw == null || effective == null) return;

        Double servings = null;
        String basis = null;

        JsonNode lm = raw.get("labelMeta");
        if (lm != null && lm.isObject()) {
            servings = parseNumberNodeOrText(lm.get("servingsPerContainer"));
            basis = (lm.get("basis") == null || lm.get("basis").isNull()) ? null : lm.get("basis").asText(null);
        }

        boolean doScale = (servings != null && servings > 1.0) && "PER_SERVING".equalsIgnoreCase(basis);
        if (!doScale) return;

        JsonNode nNode = effective.get("nutrients");
        if (nNode != null && nNode.isObject()) {
            ObjectNode n = (ObjectNode) nNode;
            for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
                JsonNode v = n.get(k);
                if (v != null && v.isNumber()) {
                    n.put(k, v.asDouble() * servings);
                }
            }
        }

        JsonNode qNode = effective.get("quantity");
        if (qNode instanceof ObjectNode q) {
            q.put("value", 1d);
            q.put("unit", "SERVING");
        }

        if (effective.has("labelMeta") && effective.get("labelMeta").isObject()) {
            ObjectNode outLm = (ObjectNode) effective.get("labelMeta");
            outLm.putNull("servingsPerContainer");
            outLm.put("basis", "WHOLE_PACKAGE");
        }
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
