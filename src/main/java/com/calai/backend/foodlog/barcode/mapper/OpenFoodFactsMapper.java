package com.calai.backend.foodlog.barcode.mapper;

import com.calai.backend.foodlog.barcode.OpenFoodFactsLang;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class OpenFoodFactsMapper {

    private OpenFoodFactsMapper() {}

    private static final double KJ_PER_KCAL = 4.184;

    public record OffResult(
            String productName,

            // per 100g/ml (carbs 已歸一化為 Available Carbs)
            Double kcalPer100g,
            Double proteinPer100g,
            Double fatPer100g,
            Double carbsPer100g,
            Double fiberPer100g,
            Double sugarPer100g,
            Double sodiumMgPer100g,

            // per serving
            Double kcalPerServing,
            Double proteinPerServing,
            Double fatPerServing,
            Double carbsPerServing,
            Double fiberPerServing,
            Double sugarPerServing,
            Double sodiumMgPerServing,

            // package size (normalized to g or ml)
            Double packageSizeValue,
            String packageSizeUnit // "g" or "ml"
    ) {}

    /** 舊呼叫點不壞：沿用原本行為（偏 generic / en fallback） */
    public static OffResult map(JsonNode root) {
        return map(root, null);
    }

    /** NEW：依 preferredLangTag 選 product_name_{lang} */
    public static OffResult map(JsonNode root, String preferredLangTag) {
        if (root == null || root.isNull() || root.isMissingNode()) return null;

        int status = root.path("status").asInt(0);
        if (status != 1) return null;

        JsonNode product = root.path("product");
        if (product.isMissingNode() || product.isNull()) return null;

        boolean isUsLabel = isUsLabel(product);
        JsonNode nutr = product.path("nutriments");

        // ===== 1) 100g =====
        Double kcal100 = resolveKcal(nutr, "100g");
        Double p100 = numberOrNull(nutr, "proteins_100g");
        Double f100 = numberOrNull(nutr, "fat_100g");
        Double fiber100 = numberOrNull(nutr, "fiber_100g");
        Double c100 = normalizeCarbs(nutr, "carbohydrates_100g", fiber100, isUsLabel);
        Double sugar100 = numberOrNull(nutr, "sugars_100g");
        Double sodiumMg100 = sodiumMgFrom(nutr, "sodium_100g", "salt_100g");

        // ===== 2) serving =====
        Double kcalSrv = resolveKcal(nutr, "serving");
        Double pSrv = numberOrNull(nutr, "proteins_serving");
        Double fSrv = numberOrNull(nutr, "fat_serving");
        Double fiberSrv = numberOrNull(nutr, "fiber_serving");
        Double cSrv = normalizeCarbs(nutr, "carbohydrates_serving", fiberSrv, isUsLabel);
        Double sugarSrv = numberOrNull(nutr, "sugars_serving");
        Double sodiumMgSrv = sodiumMgFrom(nutr, "sodium_serving", "salt_serving");

        // ===== 3) identity =====
        String name = resolveProductName(product, preferredLangTag);
        PackageSize pkg = parsePackageSize(product);

        boolean hasAnyNutrition = Stream.of(
                kcal100, p100, f100, c100, fiber100, sugar100, sodiumMg100,
                kcalSrv, pSrv, fSrv, cSrv, fiberSrv, sugarSrv, sodiumMgSrv
        ).anyMatch(Objects::nonNull);

        boolean hasAnyIdentity = (name != null && !name.isBlank()) || (pkg != null && pkg.value() > 0);

        if (!hasAnyNutrition && !hasAnyIdentity) return null;

        return new OffResult(
                name,
                kcal100, p100, f100, c100, fiber100, sugar100, sodiumMg100,
                kcalSrv, pSrv, fSrv, cSrv, fiberSrv, sugarSrv, sodiumMgSrv,
                pkg == null ? null : pkg.value(),
                pkg == null ? null : pkg.unit()
        );
    }

    // =========================
    // US 判斷
    // =========================

    private static final Set<String> US_TAGS = Set.of(
            "en:us",
            "en:usa",
            "en:united-states",
            "en:united_states",
            "en:united-states-of-america",
            "united-states",
            "united_states",
            "united-states-of-america"
    );

    private static boolean isUsLabel(JsonNode product) {
        if (product == null || product.isNull()) return false;

        // countries_tags 可能是 array，也可能被弄成 string（髒資料）
        JsonNode tags = product.get("countries_tags");
        if (tags != null && !tags.isNull() && !tags.isMissingNode()) {
            if (tags.isArray()) {
                for (JsonNode it : tags) {
                    String t = lowerTrimOrNull(it);
                    if (t != null && US_TAGS.contains(t)) return true;
                }
            } else if (tags.isTextual()) {
                String t = lowerTrimOrNull(tags);
                if (t != null && US_TAGS.contains(t)) return true;
            }
        }

        // fallback：countries 可能是 "United States, Canada"
        String countries = product.path("countries").asText("");
        return !countries.isBlank() && countries.toLowerCase(Locale.ROOT).contains("united states");
    }

    // =========================
    // Carbs Normalization
    // =========================

    /**
     * 歸一化碳水：
     * - US：Total Carbohydrate 通常包含 fiber
     * - EU：Carbohydrates 通常不含 fiber
     * 統一輸出 Available Carbohydrates（不含 fiber）
     */
    private static Double normalizeCarbs(JsonNode nutr, String carbKey, Double fiberValue, boolean isUsLabel) {
        Double rawCarbs = numberOrNull(nutr, carbKey);
        if (rawCarbs == null) return null;
        if (!isUsLabel || fiberValue == null) return rawCarbs;

        // 防負數：輸出 available carbs
        return Math.max(0.0, rawCarbs - fiberValue);
    }

    // =========================
    // Energy kcal/kJ
    // =========================

    private static Double resolveKcal(JsonNode nutr, String suffix) {
        if (nutr == null || nutr.isNull()) return null;

        Double kcal = numberOrNull(nutr, "energy-kcal_" + suffix);
        Double kj = numberOrNull(nutr, "energy-kj_" + suffix);
        Double generic = numberOrNull(nutr, "energy_" + suffix);

        String unit = lowerTrimOrNull(nutr.get("energy_unit"));
        if (isBlank(unit)) unit = lowerTrimOrNull(nutr.get("energy-kcal_unit"));
        if (isBlank(unit)) unit = lowerTrimOrNull(nutr.get("energy-kj_unit"));

        if (kcal != null) {
            // 防呆：unit=kj 且 kcal≈generic，極可能 energy-kcal_* 被誤填成 kJ
            if (isKj(unit) && generic != null && approxEqualRel(kcal, generic, 0.02)) {
                return kcal / KJ_PER_KCAL;
            }
            return kcal;
        }

        if (kj != null) return kj / KJ_PER_KCAL;
        if (generic == null) return null;

        return isKcal(unit) ? generic : generic / KJ_PER_KCAL;
    }

    // =========================
    // Sodium / Salt
    // =========================

    /**
     * OFF sodium_* / salt_* 可能是 g，也可能帶 unit=mg（或髒資料）
     * 我們統一輸出「mg sodium」
     * 優先順序：
     * 1) 看 unit 欄位（*_unit 或 base_unit）
     * 2) 沒 unit 才用 heuristic（>100 視為 mg）
     */
    private static Double sodiumMgFrom(JsonNode nutr, String sodiumKey, String saltKey) {
        Double sodium = numberOrNull(nutr, sodiumKey);
        String sodiumUnit = nutrimentUnit(nutr, sodiumKey); // e.g. sodium_unit / sodium_100g_unit

        if (sodium != null) {
            if (isMgUnit(sodiumUnit)) return sodium;          // already mg
            if (isGramUnit(sodiumUnit)) return sodium * 1000.0; // g -> mg

            // fallback heuristic（無 unit）
            if (sodium > 100.0) return sodium; // likely already mg (misfilled)
            return sodium * 1000.0;            // assume g
        }

        Double salt = numberOrNull(nutr, saltKey);
        String saltUnit = nutrimentUnit(nutr, saltKey); // e.g. salt_unit / salt_serving_unit

        if (salt != null) {
            if (isMgUnit(saltUnit)) return salt / 2.5;           // salt_mg -> sodium_mg
            if (isGramUnit(saltUnit)) return (salt / 2.5) * 1000.0; // salt_g -> sodium_g -> mg

            // fallback heuristic（無 unit）
            if (salt > 100.0) return salt / 2.5;      // likely salt_mg
            return (salt / 2.5) * 1000.0;             // assume salt_g
        }

        return null;
    }

    // =========================
    // i18n Product Name
    // =========================

    private static String resolveProductName(JsonNode product, String preferredLangTag) {
        if (preferredLangTag != null) {
            for (String lang : OpenFoodFactsLang.langCandidates(preferredLangTag)) {
                if (isBlank(lang)) continue;
                String l = lang.trim().toLowerCase(Locale.ROOT);

                String name = firstTextOrNull(product, "product_name_" + l, "product_name_" + l.replace('-', '_'));
                if (name != null) return name;
            }
        }
        return firstTextOrNull(product, "product_name", "product_name_en");
    }

    private static String firstTextOrNull(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v == null || v.isNull() || v.isMissingNode()) continue;

            String s = v.asText(null);
            if (!isBlank(s)) return s;
        }
        return null;
    }

    // =========================
    // Package Size Parsing
    // =========================

    private record PackageSize(double value, String unit) {}

    private static PackageSize parsePackageSize(JsonNode product) {
        JsonNode pq = product.get("product_quantity");
        JsonNode pqu = product.get("product_quantity_unit");

        if (pq != null && pq.isNumber() && pqu != null && pqu.isTextual()) {
            PackageSize ps = normalizeSize(pq.asDouble(), pqu.asText());
            if (ps != null) return ps;
        }

        String raw = product.path("quantity").asText(null);
        if (!isBlank(raw)) {
            ParsedQty q = parseQty(raw);
            if (q != null) return normalizeSize(q.value(), q.unitRaw());
        }

        return null;
    }

    private static PackageSize normalizeSize(double value, String unitRaw) {
        if (unitRaw == null) return null;

        String u = unitRaw.trim().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "g", "gram", "grams" -> (value > 0) ? new PackageSize(value, "g") : null;
            case "kg"                 -> (value > 0) ? new PackageSize(value * 1000.0, "g") : null;
            case "mg"                 -> (value > 0) ? new PackageSize(value / 1000.0, "g") : null;
            case "ml"                 -> (value > 0) ? new PackageSize(value, "ml") : null;
            case "l", "lt"            -> (value > 0) ? new PackageSize(value * 1000.0, "ml") : null;
            case "cl"                 -> (value > 0) ? new PackageSize(value * 10.0, "ml") : null;
            case "dl"                 -> (value > 0) ? new PackageSize(value * 100.0, "ml") : null;
            default                   -> null;
        };
    }

    // =========================
    // Number Parsing (耐髒解析)
    // =========================

    private static final Pattern P_NUM = Pattern.compile("[-+]?(?:\\d+\\.\\d+|\\d+|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern P_QTY = Pattern.compile("(?:(\\d+)\\s*[x×]\\s*)?([\\d][\\d.,']*)\\s*([a-zA-Z]+)");

    private static final Set<String> SUPPORTED_UNITS = Set.of("g", "kg", "mg", "ml", "l", "lt", "cl", "dl");

    private record ParsedQty(double value, String unitRaw) {}

    private static ParsedQty parseQty(String raw) {
        if (raw == null) return null;

        String s = raw.trim().toLowerCase(Locale.ROOT);
        Matcher m = P_QTY.matcher(s);

        ParsedQty best = null;

        while (m.find()) {
            String multRaw = m.group(1);
            String valRaw = m.group(2);
            String unit = m.group(3);

            if (valRaw == null || unit == null) continue;

            String normalizedVal = normalizeNumberish(valRaw);

            double v;
            try { v = Double.parseDouble(normalizedVal); }
            catch (NumberFormatException ignore) { continue; }

            int mult = 1;
            if (multRaw != null) {
                try { mult = Integer.parseInt(multRaw); }
                catch (NumberFormatException ignore) { mult = 1; }
            }

            if (v <= 0) continue;

            String u = unit.trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_UNITS.contains(u)) continue;

            double total = v * mult;
            ParsedQty cand = new ParsedQty(total, u);

            if (best == null || cand.value() > best.value()) {
                best = cand;
            }
        }

        return best;
    }

    private static Double numberOrNull(JsonNode node, String key) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        JsonNode v = node.path(key);
        if (v.isMissingNode() || v.isNull()) return null;

        if (v.isNumber()) return v.asDouble();

        String raw = v.asText(null);
        if (raw == null) return null;

        String s = normalizeNumberish(raw);
        Matcher m = P_NUM.matcher(s);
        if (!m.find()) return null;

        try { return Double.parseDouble(m.group()); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * 常見數字字串正規化：
     * - 1,234.5  -> 1234.5（美規）
     * - 1.234,5  -> 1234.5（歐規）
     * - 12,5     -> 12.5
     * - 1,234    -> 1234
     * - 1.234    -> 1234（若看起來像千分位）
     */
    private static String normalizeNumberish(String raw) {
        String s = raw.trim()
                .replace("\u00A0", "") // NBSP
                .replace(" ", "")
                .replace("'", "")
                .replace("’", "");

        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            // 最後出現的分隔符視為小數點
            if (lastComma > lastDot) {
                // 歐規：1.234,5 -> 1234.5
                s = s.replace(".", "").replace(',', '.');
            } else {
                // 美規：1,234.5 -> 1234.5
                s = s.replace(",", "");
            }
        } else if (lastComma >= 0) {
            // 只有逗號：可能是千分位，也可能是小數點
            if (looksLikeThousandsSeparated(s, ',')) {
                s = s.replace(",", "");
            } else {
                s = s.replace(',', '.');
            }
        } else if (lastDot >= 0) {
            // 只有點：可能是千分位，也可能是小數點
            if (looksLikeThousandsSeparated(s, '.')) {
                s = s.replace(".", "");
            }
        }

        return s;
    }

    /** 判斷是否像 1,234 / 12,345 / 1.234 / 12.345 這種千分位格式 */
    private static boolean looksLikeThousandsSeparated(String s, char sep) {
        if (s == null || s.isBlank()) return false;

        int start = (s.startsWith("-") || s.startsWith("+")) ? 1 : 0;
        if (start >= s.length()) return false;

        String body = s.substring(start);
        String[] parts = body.split(Pattern.quote(String.valueOf(sep)));
        if (parts.length < 2) return false;

        if (parts[0].isEmpty() || parts[0].length() > 3) return false;
        if (!parts[0].chars().allMatch(Character::isDigit)) return false;

        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() != 3) return false;
            if (!p.chars().allMatch(Character::isDigit)) return false;
        }
        return true;
    }

    private static String nutrimentUnit(JsonNode nutr, String nutrientKeyWithSuffix) {
        if (nutr == null || nutr.isNull() || nutrientKeyWithSuffix == null || nutrientKeyWithSuffix.isBlank()) {
            return null;
        }

        // 先找精確欄位：例如 sodium_100g_unit / salt_serving_unit
        String exact = lowerTrimOrNull(nutr.get(nutrientKeyWithSuffix + "_unit"));
        if (!isBlank(exact)) return exact;

        // 再找 base 欄位：例如 sodium_unit / salt_unit
        int idx = nutrientKeyWithSuffix.indexOf('_');
        String base = (idx > 0) ? nutrientKeyWithSuffix.substring(0, idx) : nutrientKeyWithSuffix;
        return lowerTrimOrNull(nutr.get(base + "_unit"));
    }

    private static boolean isMgUnit(String u) {
        if (u == null) return false;
        String s = u.trim().toLowerCase(Locale.ROOT);
        return s.equals("mg") || s.startsWith("milligram");
    }

    private static boolean isGramUnit(String u) {
        if (u == null) return false;
        String s = u.trim().toLowerCase(Locale.ROOT);
        return s.equals("g") || s.equals("gr") || s.startsWith("gram");
    }

    // =========================
    // Utilities
    // =========================

    private static String lowerTrimOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String s = node.asText(null);
        if (isBlank(s)) return null;
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isKj(String u) {
        return "kj".equals(u);
    }

    private static boolean isKcal(String u) {
        if (u == null) return false;
        return u.startsWith("kcal") || u.startsWith("cal");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean approxEqualRel(double a, double b, double relTol) {
        double diff = Math.abs(a - b);
        double scale = Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
        return diff / scale <= relTol;
    }
}
