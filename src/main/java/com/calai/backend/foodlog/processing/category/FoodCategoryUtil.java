package com.calai.backend.foodlog.processing.category;

import com.calai.backend.foodlog.model.FoodCategory;
import com.calai.backend.foodlog.model.FoodSubCategory;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 讀取 effective.aiMeta 內的 canonical category。
 */
public final class FoodCategoryUtil {

    private FoodCategoryUtil() {}

    public static FoodCategory readCategory(JsonNode effective) {
        if (effective == null || !effective.isObject()) {
            return FoodCategory.UNKNOWN;
        }

        JsonNode aiMeta = effective.get("aiMeta");
        if (aiMeta == null || !aiMeta.isObject()) {
            return FoodCategory.UNKNOWN;
        }

        JsonNode v = aiMeta.get("foodCategory");
        if (v == null || v.isNull()) {
            return FoodCategory.UNKNOWN;
        }

        try {
            return FoodCategory.valueOf(v.asText("").trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return FoodCategory.UNKNOWN;
        }
    }

    public static FoodSubCategory readSubCategory(JsonNode effective) {
        if (effective == null || !effective.isObject()) {
            return FoodSubCategory.UNKNOWN;
        }

        JsonNode aiMeta = effective.get("aiMeta");
        if (aiMeta == null || !aiMeta.isObject()) {
            return FoodSubCategory.UNKNOWN;
        }

        JsonNode v = aiMeta.get("foodSubCategory");
        if (v == null || v.isNull()) {
            return FoodSubCategory.UNKNOWN;
        }

        try {
            return FoodSubCategory.valueOf(v.asText("").trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return FoodSubCategory.UNKNOWN;
        }
    }

    /**
     * 低熱量放寬白名單（飲品）：
     * - BEVERAGE + TEA / COFFEE / WATER / SPARKLING_WATER
     */
    public static boolean isLowCalRelaxedBeverageCategory(JsonNode effective) {
        FoodCategory category = readCategory(effective);
        FoodSubCategory subCategory = readSubCategory(effective);

        if (category != FoodCategory.BEVERAGE) {
            return false;
        }

        return subCategory == FoodSubCategory.TEA
               || subCategory == FoodSubCategory.COFFEE
               || subCategory == FoodSubCategory.WATER
               || subCategory == FoodSubCategory.SPARKLING_WATER;
    }

    /**
     * 低熱量放寬白名單（湯）：
     * - SOUP + BROTH
     * 注意：
     * - 這裡故意不再接受 UNKNOWN
     * - 避免 generic soup 被放太寬
     */
    public static boolean isLowCalRelaxedSoupCategory(JsonNode effective) {
        FoodCategory category = readCategory(effective);
        FoodSubCategory subCategory = readSubCategory(effective);

        return category == FoodCategory.SOUP
               && subCategory == FoodSubCategory.BROTH;
    }

    /**
     * 總白名單：
     * - BEVERAGE + TEA / COFFEE / WATER / SPARKLING_WATER
     * - SOUP + BROTH
     */
    public static boolean isLowCalRelaxedCategory(JsonNode effective) {
        return isLowCalRelaxedBeverageCategory(effective)
               || isLowCalRelaxedSoupCategory(effective);
    }
}
