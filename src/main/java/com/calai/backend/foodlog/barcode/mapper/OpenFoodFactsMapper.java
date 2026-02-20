package com.calai.backend.foodlog.barcode.mapper;

import com.calai.backend.foodlog.barcode.OpenFoodFactsLang;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenFoodFactsMapper {

    private OpenFoodFactsMapper() {}

    public record OffResult(
            String productName,

            // per 100g (or 100ml in some cases; OFF often still uses *_100g keys)
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

    /** ✅ 舊呼叫點不壞：沿用原本行為（偏 generic / en fallback） */
    public static OffResult map(JsonNode root) {
        return map(root, null);
    }

    /** ✅ NEW：依 preferredLangTag 選擇 product_name_{lang} */
    public static OffResult map(JsonNode root, String preferredLangTag) {
        if (root == null || root.isNull()) return null;

        int status = root.path("status").asInt(0);
        if (status != 1) return null;

        JsonNode product = root.path("product");
        if (product.isMissingNode() || product.isNull()) return null;

        JsonNode nutr = product.path("nutriments");

        // ===== per100 =====
        Double kcal100 = numberOrNull(nutr, "energy-kcal_100g");
        if (kcal100 == null) {
            Double kj100 = numberOrNull(nutr, "energy-kj_100g");
            if (kj100 == null) kj100 = numberOrNull(nutr, "energy_100g"); // 常見：energy_100g 是 kJ
            if (kj100 != null) kcal100 = kj100 / 4.184;
        }
        Double p100 = numberOrNull(nutr, "proteins_100g");
        Double f100 = numberOrNull(nutr, "fat_100g");
        Double c100 = numberOrNull(nutr, "carbohydrates_100g");
        Double fiber100 = numberOrNull(nutr, "fiber_100g");
        Double sugar100 = numberOrNull(nutr, "sugars_100g");
        Double sodiumMg100 = sodiumMgFrom(nutr, "sodium_100g", "salt_100g");

        // ===== perServing =====
        Double kcalSrv = numberOrNull(nutr, "energy-kcal_serving");
        if (kcalSrv == null) {
            Double kjSrv = numberOrNull(nutr, "energy-kj_serving");
            if (kjSrv == null) kjSrv = numberOrNull(nutr, "energy_serving");
            if (kjSrv != null) kcalSrv = kjSrv / 4.184;
        }
        Double pSrv = numberOrNull(nutr, "proteins_serving");
        Double fSrv = numberOrNull(nutr, "fat_serving");
        Double cSrv = numberOrNull(nutr, "carbohydrates_serving");
        Double fiberSrv = numberOrNull(nutr, "fiber_serving");
        Double sugarSrv = numberOrNull(nutr, "sugars_serving");
        Double sodiumMgSrv = sodiumMgFrom(nutr, "sodium_serving", "salt_serving");

        // ===== name (i18n) =====
        String name = resolveProductName(product, preferredLangTag);

        // ===== package size =====
        PackageSize pkg = parsePackageSize(product);

        // 若完全沒有任何營養數據就視為無效
        boolean hasAnyNutrition =
                kcal100 != null || p100 != null || f100 != null || c100 != null
                || fiber100 != null || sugar100 != null || sodiumMg100 != null
                || kcalSrv != null || pSrv != null || fSrv != null || cSrv != null
                || fiberSrv != null || sugarSrv != null || sodiumMgSrv != null;

        boolean hasAnyIdentity =
                (name != null && !name.isBlank())
                || (pkg != null && pkg.value > 0);

        if (!hasAnyNutrition && !hasAnyIdentity) return null;

        // ✅ 回 OffResult（營養可全 null），後端再標 warning: NO_NUTRITION_DATA
        return new OffResult(
                name,
                kcal100, p100, f100, c100, fiber100, sugar100, sodiumMg100,
                kcalSrv, pSrv, fSrv, cSrv, fiberSrv, sugarSrv, sodiumMgSrv,
                pkg == null ? null : pkg.value,
                pkg == null ? null : pkg.unit
        );
    }

    // =========================
    // i18n product name
    // =========================

    /**
     * preferredLangTag 可以是：
     * - "fr" / "pt-BR" / "zh-TW"
     * - 或 Accept-Language: "fr-CA,fr;q=0.9,en;q=0.8"
     */
    private static String resolveProductName(JsonNode product, String preferredLangTag) {
        // 用 Set 去重，保留插入順序
        Set<String> keys = new LinkedHashSet<>();

        // 1) preferred language keys
        for (String lang : OpenFoodFactsLang.langCandidates(preferredLangTag)) {
            if (lang == null) continue;
            String l = lang.trim().toLowerCase(Locale.ROOT);
            if (l.isEmpty()) continue;

            keys.add("product_name_" + l);
            keys.add("product_name_" + l.replace('-', '_'));
        }

        // 2) generic
        keys.add("product_name");

        // 3) english fallback
        keys.add("product_name_en");

        return firstTextOrNull(product, keys.toArray(new String[0]));
    }

    /**
     * OFF sodium_* 通常是「g」，我們要輸出「mg」：
     * sodiumMg = sodium_g * 1000
     * 若沒有 sodium，用 salt（g）推：sodium_g ≈ salt_g / 2.5
     */
    private static Double sodiumMgFrom(JsonNode nutr, String sodiumKey, String saltKey) {
        Double sodiumG = numberOrNull(nutr, sodiumKey);
        if (sodiumG != null) return sodiumG * 1000.0;

        Double saltG = numberOrNull(nutr, saltKey);
        if (saltG != null) return (saltG / 2.5) * 1000.0;

        return null;
    }

    private static String firstTextOrNull(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v == null || v.isNull()) continue;
            String s = v.asText(null);
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    // =========================
    // package size parsing
    // =========================

    private static final class PackageSize {
        final double value; // normalized to g or ml
        final String unit;  // "g" or "ml"
        PackageSize(double value, String unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    private static PackageSize parsePackageSize(JsonNode product) {
        // 1) product_quantity + product_quantity_unit (最乾淨)
        JsonNode pq = product.get("product_quantity");
        JsonNode pqu = product.get("product_quantity_unit");

        if (pq != null && pq.isNumber() && pqu != null && pqu.isTextual()) {
            PackageSize ps = normalizeSize(pq.asDouble(), pqu.asText());
            if (ps != null) return ps;
        }

        // 2) quantity string (次選，較髒) e.g. "330 ml", "1 kg"
        String raw = product.path("quantity").asText(null);
        if (raw != null && !raw.isBlank()) {
            ParsedQty q = parseQty(raw);
            if (q == null) return null;
            return normalizeSize(q.value, q.unitRaw);
        }

        return null;
    }

    private static PackageSize normalizeSize(double value, String unitRaw) {
        if (unitRaw == null) return null;

        String u = unitRaw.trim().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "" -> null;
            // normalize common units → g / ml
            case "g", "gram", "grams" -> (value > 0) ? new PackageSize(value, "g") : null;
            case "kg" -> (value > 0) ? new PackageSize(value * 1000.0, "g") : null;
            case "mg" -> (value > 0) ? new PackageSize(value / 1000.0, "g") : null;
            case "ml" -> (value > 0) ? new PackageSize(value, "ml") : null;
            case "l", "lt" -> (value > 0) ? new PackageSize(value * 1000.0, "ml") : null;
            case "cl" -> (value > 0) ? new PackageSize(value * 10.0, "ml") : null;
            case "dl" -> (value > 0) ? new PackageSize(value * 100.0, "ml") : null;
            default ->
                // 其他單位先不處理（oz / fl oz / lb...），避免算錯
                null;
        };

    }

    // =========================
    // number parsing (耐髒)
    // =========================

    private static final Pattern P_NUM = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern P_QTY = Pattern.compile("(?:(\\d+)\\s*[x×]\\s*)?(\\d+(?:[\\.,]\\d+)?)\\s*([a-zA-Z]+)");

    private static final class ParsedQty {
        final double value;
        final String unitRaw;
        ParsedQty(double value, String unitRaw) {
            this.value = value;
            this.unitRaw = unitRaw;
        }
    }

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
            catch (Exception ignore) { continue; }

            int mult = 1;
            if (multRaw != null) {
                try { mult = Integer.parseInt(multRaw); }
                catch (Exception ignore) { mult = 1; }
            }

            if (v <= 0) continue;

            String u = unit.trim().toLowerCase(Locale.ROOT);
            if (!isSupportedUnit(u)) continue;

            double total = v * mult;
            ParsedQty cand = new ParsedQty(total, u);

            if (best == null || cand.value > best.value) {
                best = cand;
            }
        }

        return best;
    }

    private static boolean isSupportedUnit(String u) {
        return u.equals("g") || u.equals("kg") || u.equals("mg")
               || u.equals("ml") || u.equals("l") || u.equals("lt")
               || u.equals("cl") || u.equals("dl");
    }

    private static Double numberOrNull(JsonNode node, String key) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.path(key);
        if (v.isMissingNode() || v.isNull()) return null;

        if (v.isNumber()) return v.asDouble();

        // 例如 "12.3 g" / "0,5" / "≈3.2"
        String raw = v.asText(null);
        if (raw == null) return null;

        String s = raw.trim().replace(',', '.');
        Matcher m = P_NUM.matcher(s);
        if (!m.find()) return null;

        try { return Double.parseDouble(m.group()); }
        catch (Exception e) { return null; }
    }
}
