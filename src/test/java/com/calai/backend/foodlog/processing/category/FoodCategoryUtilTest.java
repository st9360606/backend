package com.calai.backend.foodlog.processing.category;

import com.calai.backend.foodlog.model.FoodCategory;
import com.calai.backend.foodlog.model.FoodSubCategory;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoodCategoryUtilTest {

    @Test
    void should_read_category_and_subcategory_from_aiMeta() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode aiMeta = root.putObject("aiMeta");
        aiMeta.put("foodCategory", "BEVERAGE");
        aiMeta.put("foodSubCategory", "TEA");

        Assertions.assertEquals(FoodCategory.BEVERAGE, FoodCategoryUtil.readCategory(root));
        Assertions.assertEquals(FoodSubCategory.TEA, FoodCategoryUtil.readSubCategory(root));
        assertTrue(FoodCategoryUtil.isLowCalRelaxedCategory(root));
    }

    @Test
    void should_return_unknown_when_aiMeta_missing() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();

        assertEquals(FoodCategory.UNKNOWN, FoodCategoryUtil.readCategory(root));
        assertEquals(FoodSubCategory.UNKNOWN, FoodCategoryUtil.readSubCategory(root));
        assertFalse(FoodCategoryUtil.isLowCalRelaxedCategory(root));
    }

    @Test
    void soup_broth_should_be_relaxed_category() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode aiMeta = root.putObject("aiMeta");
        aiMeta.put("foodCategory", "SOUP");
        aiMeta.put("foodSubCategory", "BROTH");

        assertTrue(FoodCategoryUtil.isLowCalRelaxedCategory(root));
    }
}