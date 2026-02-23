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
     * OFF sodium_* / salt_* 多數情況是「g」
     * 我們統一輸出「mg」
     *
     * 防呆：
     * - 若數值 > 100，幾乎可視為廠商把 mg 誤填到 g 欄位（不論 100g/serving）
     * -> 視為已是 mg，不再乘 1000
     */
    private static Double sodiumMgFrom(JsonNode nutr, String sodiumKey, String saltKey) {
        Double sodium = numberOrNull(nutr, sodiumKey);
        if (sodium != null) {
            if (sodium > 100.0) return sodium;      // already mg (misfilled)
            return sodium * 1000.0;                 // g -> mg
        }

        Double salt = numberOrNull(nutr, saltKey);
        if (salt != null) {
            if (salt > 100.0) return salt / 2.5;    // salt_mg -> sodium_mg
            return (salt / 2.5) * 1000.0;           // salt_g -> sodium_g -> mg
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

    private static final Pattern P_NUM = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern P_QTY = Pattern.compile("(?:(\\d+)\\s*[x×]\\s*)?(\\d+(?:[\\.,]\\d+)?)\\s*([a-zA-Z]+)");

    private static final Set<String> SUPPORTED_UNITS = Set.of("g", "kg", "mg", "ml", "l", "lt", "cl", "dl");

    private record ParsedQty(double value, String unitRaw) {}

    private static ParsedQty parseQty(String raw) {
        if (raw == null) return null;

        String s = raw.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        Matcher m = P_QTY.matcher(s);

        ParsedQty best = null;

        while (m.find()) {
            String multRaw = m.group(1);
            String valRaw = m.group(2);
            String unit = m.group(3);

            if (valRaw == null || unit == null) continue;

            double v;
            try { v = Double.parseDouble(valRaw); }
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

        String s = raw.trim().replace(',', '.');
        Matcher m = P_NUM.matcher(s);
        if (!m.find()) return null;

        try { return Double.parseDouble(m.group()); }
        catch (NumberFormatException e) { return null; }
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
