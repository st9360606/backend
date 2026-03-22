package com.calai.backend.foodlog.barcode.openfoodfacts.support;

import com.calai.backend.foodlog.barcode.openfoodfacts.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.model.FoodCategory;
import com.calai.backend.foodlog.model.FoodSubCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenFoodFactsCategoryResolverTest {

    @Test
    void green_tea_should_resolve_to_beverage_tea() {
        OffResult off = new OffResult(
                "Green Tea",
                0.0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                500.0, "ml",
                List.of("tea", "beverages")
        );

        OpenFoodFactsCategoryResolver.Resolved r = OpenFoodFactsCategoryResolver.resolve(off);

        assertEquals(FoodCategory.BEVERAGE, r.category());
        assertEquals(FoodSubCategory.TEA, r.subCategory());
    }

    @Test
    void sparkling_water_should_resolve_to_beverage_sparkling_water() {
        OffResult off = new OffResult(
                "Sparkling Water",
                0.0, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                330.0, "ml",
                List.of("sparkling-waters", "waters", "beverages")
        );

        OpenFoodFactsCategoryResolver.Resolved r = OpenFoodFactsCategoryResolver.resolve(off);

        assertEquals(FoodCategory.BEVERAGE, r.category());
        assertEquals(FoodSubCategory.SPARKLING_WATER, r.subCategory());
    }

    @Test
    void clear_soup_should_resolve_to_soup_broth() {
        OffResult off = new OffResult(
                "Clear Soup",
                25.0, 1.0, 0.3, 3.0, null, null, 350.0,
                null, null, null, null, null, null, null,
                250.0, "ml",
                List.of("soups", "broths")
        );

        OpenFoodFactsCategoryResolver.Resolved r = OpenFoodFactsCategoryResolver.resolve(off);

        assertEquals(FoodCategory.SOUP, r.category());
        assertEquals(FoodSubCategory.BROTH, r.subCategory());
    }
}