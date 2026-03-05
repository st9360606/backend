package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.model.FoodSubCategory;
import com.calai.backend.foodlog.util.FoodCategoryUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

public final class GeminiNutritionQualityGate {

    private GeminiNutritionQualityGate() {
    }

    private enum RelaxedLowCalProfile {
        NONE,
        WATER,
        TEA_OR_COFFEE,
        BROTH
    }

    public static boolean isAcceptablePhoto(JsonNode effectiveOrRaw) {
        if (effectiveOrRaw == null || !effectiveOrRaw.isObject()) {
            return false;
        }

        JsonNode n = effectiveOrRaw.path("nutrients");
        if (!n.isObject()) {
            return false;
        }

        Double kcal = numOrNull(n.get("kcal"));
        Double p = numOrNull(n.get("protein"));
        Double f = numOrNull(n.get("fat"));
        Double c = numOrNull(n.get("carbs"));

        // 1) kcal + 三大營養素全都沒有 => 不可
        if (kcal == null && p == null && f == null && c == null) {
            return false;
        }

        // 2) foodName 有值時，核心四大要完整
        String name = effectiveOrRaw.path("foodName").isNull()
                ? ""
                : effectiveOrRaw.path("foodName").asText("");
        String lname = name.toLowerCase(Locale.ROOT);
        boolean hasName = !lname.isBlank();

        boolean hasCompleteCoreQuartet =
                (kcal != null && p != null && f != null && c != null);

        if (hasName && !hasCompleteCoreQuartet) {
            return false;
        }

        // 3) quantity
        JsonNode q = effectiveOrRaw.path("quantity");
        String unit = normalizeUnit(q.path("unit").asText("SERVING"));
        Double qty = numOrNull(q.get("value"));
        if (qty == null || qty <= 0) {
            qty = 1.0;
        }

        // 4) zero / zero-like 文字符號
        boolean looksLikeZero = containsAny(lname,
                "0 kcal", "zero sugar", "zero calorie", "zero calories",
                "無糖", "无糖", "零卡", "零糖", "0糖"
        );

        // 5) category / text profile
        RelaxedLowCalProfile categoryProfile = resolveCategoryProfile(effectiveOrRaw);
        RelaxedLowCalProfile textProfile =
                (categoryProfile == RelaxedLowCalProfile.NONE)
                        ? resolveStrongTextProfile(lname)
                        : RelaxedLowCalProfile.NONE;

        boolean relaxedByCategoryAndNutrients =
                isRelaxedLowCalProfile(categoryProfile, kcal, p, f, c, unit, qty);

        boolean relaxedByTextAndNutrients =
                isRelaxedLowCalProfile(textProfile, kcal, p, f, c, unit, qty);

        // ✅ 修正點：
        // 不再用 "< 80 kcal / serving" 一刀切
        // 只擋明顯極端異常值
        if (!looksLikeZero && kcal != null) {
            boolean relaxedLowCal = relaxedByCategoryAndNutrients || relaxedByTextAndNutrients;

            if (!relaxedLowCal && "SERVING".equals(unit) && qty >= 1.0) {
                double est = estimateCalories(p, f, c);

                // 只有在「標示熱量極低，但 macros 明顯撐不起來 / 對不上」時才 reject
                if (kcal < 8.0 && est > 25.0) {
                    return false;
                }
            }
        }

        // 6) 能量一致性（至少 2 個 macro 才檢查）
        int macroCount = 0;
        if (p != null) macroCount++;
        if (f != null) macroCount++;
        if (c != null) macroCount++;

        if (kcal != null && macroCount >= 2) {
            double est = estimateCalories(p, f, c);
            double diff = Math.abs(est - kcal);
            double tol = Math.max(120.0, kcal * 0.40);
            if (diff > tol) {
                return false;
            }
        }

        return true;
    }

    private static double estimateCalories(Double protein, Double fat, Double carbs) {
        double est = 0.0;
        if (protein != null) est += 4.0 * protein;
        if (carbs != null) est += 4.0 * carbs;
        if (fat != null) est += 9.0 * fat;
        return est;
    }

    private static RelaxedLowCalProfile resolveCategoryProfile(JsonNode effectiveOrRaw) {
        if (FoodCategoryUtil.isLowCalRelaxedSoupCategory(effectiveOrRaw)) {
            return RelaxedLowCalProfile.BROTH;
        }

        if (!FoodCategoryUtil.isLowCalRelaxedBeverageCategory(effectiveOrRaw)) {
            return RelaxedLowCalProfile.NONE;
        }

        FoodSubCategory sub = FoodCategoryUtil.readSubCategory(effectiveOrRaw);

        if (sub == FoodSubCategory.WATER || sub == FoodSubCategory.SPARKLING_WATER) {
            return RelaxedLowCalProfile.WATER;
        }

        if (sub == FoodSubCategory.TEA || sub == FoodSubCategory.COFFEE) {
            return RelaxedLowCalProfile.TEA_OR_COFFEE;
        }

        return RelaxedLowCalProfile.NONE;
    }

    /**
     * 只保留強訊號詞，避免 generic tea/coffee/water/soup 誤判。
     */
    private static RelaxedLowCalProfile resolveStrongTextProfile(String lname) {
        if (lname == null || lname.isBlank()) {
            return RelaxedLowCalProfile.NONE;
        }

        if (containsAny(lname,
                "sparkling water", "soda water", "carbonated water",
                "mineral water", "drinking water", "purified water", "spring water",
                "氣泡水", "气泡水", "礦泉水", "矿泉水", "純水", "纯水", "飲用水", "饮用水",
                "炭酸水", "ミネラルウォーター", "생수", "광천수",
                "agua con gas", "agua mineral", "eau gazeuse", "eau minérale", "mineralwasser"
        )) {
            return RelaxedLowCalProfile.WATER;
        }

        if (containsAny(lname,
                "green tea", "black tea", "oolong tea", "jasmine tea", "iced tea",
                "black coffee", "americano", "espresso", "cold brew",
                "綠茶", "绿茶", "紅茶", "红茶", "烏龍茶", "乌龙茶", "茉莉花茶",
                "黑咖啡", "美式咖啡", "濃縮咖啡", "浓缩咖啡", "冷萃咖啡",
                "緑茶", "ウーロン茶", "ブラックコーヒー", "エスプレッソ",
                "녹차", "홍차", "우롱차", "블랙커피", "아메리카노", "에스프레소"
        )) {
            return RelaxedLowCalProfile.TEA_OR_COFFEE;
        }

        if (containsAny(lname,
                "clear soup", "clear broth", "broth", "consommé", "consomme",
                "清湯", "清汤", "高湯", "高汤", "高湯底", "高汤底",
                "コンソメ", "ブロス", "육수", "맑은 국물",
                "bouillon", "brodo", "caldo claro"
        )) {
            return RelaxedLowCalProfile.BROTH;
        }

        return RelaxedLowCalProfile.NONE;
    }

    private static boolean isRelaxedLowCalProfile(
            RelaxedLowCalProfile profile,
            Double kcal,
            Double protein,
            Double fat,
            Double carbs,
            String unit,
            Double qty
    ) {
        if (profile == RelaxedLowCalProfile.NONE || kcal == null) {
            return false;
        }

        String normalizedUnit = normalizeUnit(unit);
        double quantity = (qty == null || qty <= 0) ? 1.0 : qty;

        if ("GRAM".equals(normalizedUnit) || "ML".equals(normalizedUnit)) {
            Double kcal100 = per100(kcal, quantity);
            Double p100 = per100(protein, quantity);
            Double f100 = per100(fat, quantity);
            Double c100 = per100(carbs, quantity);

            return switch (profile) {
                case WATER -> within(kcal100, 8.0)
                              && within(p100, 1.0)
                              && within(f100, 0.5)
                              && within(c100, 2.0);

                case TEA_OR_COFFEE -> within(kcal100, 20.0)
                                      && within(p100, 3.0)
                                      && within(f100, 1.5)
                                      && within(c100, 5.0);

                case BROTH -> within(kcal100, 35.0)
                              && within(p100, 6.0)
                              && within(f100, 3.0)
                              && within(c100, 6.0);

                default -> false;
            };
        }

        // SERVING / fallback
        return switch (profile) {
            case WATER -> within(kcal, 12.0)
                          && within(protein, 1.0)
                          && within(fat, 0.5)
                          && within(carbs, 3.0);

            case TEA_OR_COFFEE -> within(kcal, 35.0)
                                  && within(protein, 4.0)
                                  && within(fat, 2.0)
                                  && within(carbs, 8.0);

            case BROTH -> within(kcal, 60.0)
                          && within(protein, 8.0)
                          && within(fat, 4.0)
                          && within(carbs, 10.0);

            default -> false;
        };
    }

    private static Double per100(Double value, double qty) {
        if (value == null || qty <= 0) {
            return null;
        }
        return value * 100.0 / qty;
    }

    private static boolean within(Double value, double max) {
        return value == null || value <= max;
    }

    private static boolean containsAny(String text, String... candidates) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String c : candidates) {
            if (c != null && !c.isBlank() && text.contains(c.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeUnit(String unit) {
        if (unit == null) {
            return "SERVING";
        }
        String u = unit.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "G", "GRAM", "GRAMS" -> "GRAM";
            case "ML", "MILLILITER", "MILLILITERS" -> "ML";
            default -> "SERVING";
        };
    }

    private static Double numOrNull(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            String s = v.asText("").trim().replace(',', '.');
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(s.replaceAll("[^0-9.\\-]", ""));
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }
}
