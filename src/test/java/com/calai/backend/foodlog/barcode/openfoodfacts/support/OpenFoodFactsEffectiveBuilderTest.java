package com.calai.backend.foodlog.barcode.openfoodfacts.support;

import com.calai.backend.foodlog.barcode.openfoodfacts.mapper.OpenFoodFactsMapper.OffResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenFoodFactsEffectiveBuilderTest {

    @Test
    void hasUsableNutrition_should_return_true_when_zero_values_present() {
        OffResult off = new OffResult(
                "Unsweetened Green Tea",
                0.0, 0.0, 0.0, 0.0, null, null, 0.0,
                null, null, null, null, null, null, null,
                600.0, "ml",
                List.of() // ✅ 新增 categoryTags
        );

        assertTrue(OpenFoodFactsEffectiveBuilder.hasUsableNutrition(off));
    }

    @Test
    void hasUsableNutrition_should_return_false_when_all_nutrition_fields_are_null() {
        OffResult off = new OffResult(
                "Tea Product",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                600.0, "ml",
                List.of() // ✅ 新增 categoryTags
        );

        assertFalse(OpenFoodFactsEffectiveBuilder.hasUsableNutrition(off));
    }
}