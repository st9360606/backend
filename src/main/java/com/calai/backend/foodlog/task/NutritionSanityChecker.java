package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NutritionSanityChecker {

    private static final double MAX_GRAM_OR_ML = 3000.0;
    private static final double MAX_SERVING = 10.0;
    private static final double MAX_PIECE = 20.0;
    private static final double MAX_CONTAINER = 3.0;

    private static final double MAX_KCAL = 2500.0;
    private static final double MAX_PROTEIN = 250.0;
    private static final double MAX_FAT = 200.0;
    private static final double MAX_CARBS = 400.0;
    private static final double MAX_SUGAR = 200.0;
    private static final double MAX_FIBER = 100.0;

    private static final double ENERGY_MISMATCH_RATIO = 0.30;
    private static final double ENERGY_MISMATCH_ABS_KCAL = 150.0;

    private static final Map<String, String> UNIT_ALIAS = Map.ofEntries(
            Map.entry("G", "GRAM"),
            Map.entry("GRAM", "GRAM"),
            Map.entry("GRAMS", "GRAM"),
            Map.entry("GM", "GRAM"),

            Map.entry("ML", "ML"),
            Map.entry("MILLILITER", "ML"),
            Map.entry("MILLILITERS", "ML"),

            Map.entry("SERVING", "SERVING"),
            Map.entry("SERVINGS", "SERVING"),
            Map.entry("PORTION", "SERVING"),
            Map.entry("PORTIONS", "SERVING"),

            // 對齊 Prompt logical units
            Map.entry("PACK", "PACK"),
            Map.entry("PACKAGE", "PACK"),
            Map.entry("BOTTLE", "BOTTLE"),
            Map.entry("CAN", "CAN"),
            Map.entry("PIECE", "PIECE"),
            Map.entry("PCS", "PIECE"),
            Map.entry("PC", "PIECE"),
            Map.entry("UNIT", "PIECE"),
            Map.entry("WHOLE", "PIECE")
    );

    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern FIRST_ALPHA_TOKEN = Pattern.compile("([A-Za-z]+)");

    public void apply(ObjectNode effective) {
        if (effective == null) return;

        ObjectNode nutrients = obj(effective, "nutrients");
        if (nutrients == null) return;

        ObjectNode q = obj(effective, "quantity");
        String unitRaw = q == null ? null : text(q.get("unit"));
        Double qty = numFlexible(q == null ? null : q.get("value"));

        UnitNorm unitNorm = normalizeUnitWithFlag(unitRaw);

        // 只有 unit 有值但無法辨識時，才加 UNIT_UNKNOWN
        if (unitNorm.unknownUnit && qty != null) {
            addWarning(effective, FoodLogWarning.UNIT_UNKNOWN);
        }

        String unit = unitNorm.unit;

        // nutrients 全 null：只保留 UNIT_UNKNOWN，不做其他 outlier
        if (allNutrientsNull(nutrients)) return;

        if (unit != null && qty != null) {
            if (("GRAM".equals(unit) || "ML".equals(unit)) && qty > MAX_GRAM_OR_ML) {
                addWarning(effective, FoodLogWarning.QUANTITY_OUTLIER);
            }
            if ("SERVING".equals(unit) && qty > MAX_SERVING) {
                addWarning(effective, FoodLogWarning.QUANTITY_OUTLIER);
            }
            if ("PIECE".equals(unit) && qty > MAX_PIECE) {
                addWarning(effective, FoodLogWarning.QUANTITY_OUTLIER);
            }
            if (("PACK".equals(unit) || "BOTTLE".equals(unit) || "CAN".equals(unit)) && qty > MAX_CONTAINER) {
                addWarning(effective, FoodLogWarning.QUANTITY_OUTLIER);
            }
        }

        Double kcal = numFlexible(nutrients.get("kcal"));
        if (isFiniteNonNegative(kcal) && kcal > MAX_KCAL) {
            addWarning(effective, FoodLogWarning.KCAL_OUTLIER);
        }

        Double protein = numFlexible(nutrients.get("protein"));
        Double fat = numFlexible(nutrients.get("fat"));
        Double carbs = numFlexible(nutrients.get("carbs"));
        Double sugar = numFlexible(nutrients.get("sugar"));
        Double fiber = numFlexible(nutrients.get("fiber"));

        boolean macroOutlier =
                (isFiniteNonNegative(protein) && protein > MAX_PROTEIN) ||
                (isFiniteNonNegative(fat) && fat > MAX_FAT) ||
                (isFiniteNonNegative(carbs) && carbs > MAX_CARBS) ||
                (isFiniteNonNegative(sugar) && sugar > MAX_SUGAR) ||
                (isFiniteNonNegative(fiber) && fiber > MAX_FIBER);

        if (macroOutlier) addWarning(effective, FoodLogWarning.MACRO_OUTLIER);

        if (isFiniteNonNegative(kcal) && atLeastTwoPresent(protein, carbs, fat)) {
            double kcalEst = finiteOrZero(protein) * 4.0
                             + finiteOrZero(carbs) * 4.0
                             + finiteOrZero(fat) * 9.0;

            double diff = Math.abs(kcal - kcalEst);
            double denom = Math.max(1.0, Math.max(kcal, kcalEst));
            double ratio = diff / denom;

            if (diff >= ENERGY_MISMATCH_ABS_KCAL && ratio >= ENERGY_MISMATCH_RATIO) {
                addWarning(effective, FoodLogWarning.MACRO_OUTLIER);
            }
        }
    }

    private static boolean atLeastTwoPresent(Double a, Double b, Double c) {
        int cnt = 0;
        if (isFiniteNonNegative(a)) cnt++;
        if (isFiniteNonNegative(b)) cnt++;
        if (isFiniteNonNegative(c)) cnt++;
        return cnt >= 2;
    }

    private static double finiteOrZero(Double v) {
        return isFiniteNonNegative(v) ? v : 0.0;
    }

    private static UnitNorm normalizeUnitWithFlag(String unitRaw) {
        if (unitRaw == null) return new UnitNorm(null, false);

        String u0 = unitRaw.trim();
        if (u0.isEmpty()) return new UnitNorm(null, false);

        Matcher m = FIRST_ALPHA_TOKEN.matcher(u0);
        if (!m.find()) return new UnitNorm(null, true);

        String u = m.group(1).toUpperCase(Locale.ROOT);
        String norm = UNIT_ALIAS.get(u);
        if (norm != null) return new UnitNorm(norm, false);

        return new UnitNorm(null, true);
    }

    private record UnitNorm(String unit, boolean unknownUnit) {}

    private static boolean isFiniteNonNegative(Double v) {
        return v != null && Double.isFinite(v) && v >= 0.0;
    }

    private static boolean allNutrientsNull(ObjectNode n) {
        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) return false;
        }
        return true;
    }

    private static void addWarning(ObjectNode eff, FoodLogWarning w) {
        ArrayNode out = ensureWarningsArray(eff);
        for (JsonNode n : out) {
            if (n != null && w.name().equalsIgnoreCase(n.asText())) return;
        }
        out.add(w.name());
    }

    private static ArrayNode ensureWarningsArray(ObjectNode eff) {
        JsonNode arr = eff.get("warnings");
        if (arr != null && arr.isArray()) return (ArrayNode) arr;
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        eff.set("warnings", out);
        return out;
    }

    private static ObjectNode obj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        return (n != null && n.isObject()) ? (ObjectNode) n : null;
    }

    /**
     * 支援：
     * - number: 123.4
     * - string: "123.4" / "100 g" / "~300" / "1/2" / "null"
     */
    private static Double numFlexible(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asDouble();

        if (v.isTextual()) {
            String s = v.asText();
            if (s == null) return null;

            String t = s.trim().replace(",", "").toLowerCase(Locale.ROOT);
            if (t.isEmpty()) return null;

            if (t.equals("null") || t.equals("n/a") || t.equals("na") || t.equals("unknown")) return null;

            int slash = t.indexOf('/');
            if (slash > 0 && slash < t.length() - 1) {
                try {
                    double a = Double.parseDouble(t.substring(0, slash).trim());
                    double b = Double.parseDouble(t.substring(slash + 1).trim());
                    if (b != 0.0) return a / b;
                } catch (Exception ignored) {}
            }

            Matcher m = FIRST_NUMBER.matcher(t);
            if (m.find()) {
                try { return Double.parseDouble(m.group(1)); }
                catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    private static String text(JsonNode v) {
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
