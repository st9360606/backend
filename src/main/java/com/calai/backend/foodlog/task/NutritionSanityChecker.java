package com.calai.backend.foodlog.task;

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

    private static final double MAX_KCAL = 2500.0;
    private static final double MAX_PROTEIN = 250.0;
    private static final double MAX_FAT = 200.0;
    private static final double MAX_CARBS = 400.0;
    private static final double MAX_SUGAR = 200.0;
    private static final double MAX_FIBER = 100.0;

    // ✅ 移除：SODIUM_* 相關門檻（避免誤會）

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
            Map.entry("PORTIONS", "SERVING")
    );

    // ✅ 只抓非負數字（含小數）："100 g" / "~300" / "約 120"
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    // ✅ 單位正規化：取第一段英文字母 token（"g/ml" -> "g", "grams)" -> "grams"）
    private static final Pattern FIRST_ALPHA_TOKEN = Pattern.compile("([A-Za-z]+)");

    public void apply(ObjectNode effective) {
        if (effective == null) return;

        ObjectNode nutrients = obj(effective, "nutrients");
        if (nutrients == null) return;

        // ===== quantity / unit normalization（先做，避免 all-null 直接 return 吃掉 UNIT_UNKNOWN）=====
        ObjectNode q = obj(effective, "quantity");
        String unitRaw = q == null ? null : text(q.get("unit"));
        Double qty = numFlexible(q == null ? null : q.get("value"));

        UnitNorm unitNorm = normalizeUnitWithFlag(unitRaw);

        // ✅ 只有「unit 有值但無法辨識」且 qty 也有值，才加 UNIT_UNKNOWN
        if (unitNorm.unknownUnit && qty != null) {
            addWarning(effective, FoodLogWarning.UNIT_UNKNOWN);
        }

        String unit = unitNorm.unit; // GRAM/ML/SERVING or null

        // nutrients 全 null：只保留 UNIT_UNKNOWN（不做 outlier）
        if (allNutrientsNull(nutrients)) return;

        // ===== qty outlier =====
        if (unit != null && qty != null) {
            if (("GRAM".equals(unit) || "ML".equals(unit)) && qty > MAX_GRAM_OR_ML) {
                addWarning(effective, FoodLogWarning.QUANTITY_OUTLIER);
            }
            if ("SERVING".equals(unit) && qty > MAX_SERVING) {
                addWarning(effective, FoodLogWarning.QUANTITY_OUTLIER);
            }
        }

        // ===== kcal outlier =====
        Double kcal = numFlexible(nutrients.get("kcal"));
        if (isFiniteNonNegative(kcal) && kcal > MAX_KCAL) {
            addWarning(effective, FoodLogWarning.KCAL_OUTLIER);
        }

        // ===== macro outlier =====
        Double protein = numFlexible(nutrients.get("protein"));
        Double fat     = numFlexible(nutrients.get("fat"));
        Double carbs   = numFlexible(nutrients.get("carbs"));
        Double sugar   = numFlexible(nutrients.get("sugar"));
        Double fiber   = numFlexible(nutrients.get("fiber"));

        boolean macroOutlier =
                (isFiniteNonNegative(protein) && protein > MAX_PROTEIN) ||
                (isFiniteNonNegative(fat) && fat > MAX_FAT) ||
                (isFiniteNonNegative(carbs) && carbs > MAX_CARBS) ||
                (isFiniteNonNegative(sugar) && sugar > MAX_SUGAR) ||
                (isFiniteNonNegative(fiber) && fiber > MAX_FIBER);

        if (macroOutlier) addWarning(effective, FoodLogWarning.MACRO_OUTLIER);

        // ✅ 移除：SODIUM_OUTLIER / SODIUM_UNIT_SUSPECT（避免誤會）

        // ===== kcal vs macro mismatch（抓單位錯/亂猜）=====
        if (isFiniteNonNegative(kcal) && atLeastTwoPresent(protein, carbs, fat)) {
            double kcalEst = finiteOrZero(protein) * 4.0
                             + finiteOrZero(carbs) * 4.0
                             + finiteOrZero(fat) * 9.0;

            double diff = Math.abs(kcal - kcalEst);
            double denom = Math.max(1.0, Math.max(kcal, kcalEst));
            double ratio = diff / denom;

            if (diff >= ENERGY_MISMATCH_ABS_KCAL && ratio >= ENERGY_MISMATCH_RATIO) {
                // TODO：若未來你要更精準，建議新增 ENERGY_MISMATCH enum
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

        // ✅ 有填 unit 但完全沒有英文字母（例如 "克" / "毫升" / "杯"）→ 視為未知單位
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
     * - string: "123.4" / " 1,234.5 " / "100 g" / "~300" / "1/2" / "null"
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
