package com.calai.backend.foodlog.provider;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiProviderClientNutritionQualityGateTest {

    @Test
    void low_kcal_tea_should_pass_when_category_is_beverage_tea() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("foodName", "緑茶");

        ObjectNode quantity = root.putObject("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = root.putObject("nutrients");
        nutrients.put("kcal", 12.0);
        nutrients.put("protein", 0.0);
        nutrients.put("fat", 0.0);
        nutrients.put("carbs", 2.5);
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");
        nutrients.putNull("sodium");

        ObjectNode aiMeta = root.putObject("aiMeta");
        aiMeta.put("foodCategory", "BEVERAGE");
        aiMeta.put("foodSubCategory", "TEA");

        // ✅ 改成新的 top-level class
        assertTrue(GeminiNutritionQualityGate.isAcceptablePhoto(root));
    }

    @Test
    void low_kcal_main_meal_should_fail_when_not_relaxed_category() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("foodName", "Chicken Rice");

        ObjectNode quantity = root.putObject("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = root.putObject("nutrients");
        nutrients.put("kcal", 54.0);
        nutrients.put("protein", 5.0);
        nutrients.put("fat", 1.0);
        nutrients.put("carbs", 8.0);
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");
        nutrients.putNull("sodium");

        ObjectNode aiMeta = root.putObject("aiMeta");
        aiMeta.put("foodCategory", "MEAL");
        aiMeta.put("foodSubCategory", "UNKNOWN");

        // ✅ 改成新的 top-level class
        assertFalse(GeminiNutritionQualityGate.isAcceptablePhoto(root));
    }

    @Test
    void old_data_without_category_should_still_use_keyword_fallback() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("foodName", "Black Tea");

        ObjectNode quantity = root.putObject("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = root.putObject("nutrients");
        nutrients.put("kcal", 10.0);
        nutrients.put("protein", 0.0);
        nutrients.put("fat", 0.0);
        nutrients.put("carbs", 2.0);
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");
        nutrients.putNull("sodium");

        // ✅ 改成新的 top-level class
        assertTrue(GeminiNutritionQualityGate.isAcceptablePhoto(root));
    }
}