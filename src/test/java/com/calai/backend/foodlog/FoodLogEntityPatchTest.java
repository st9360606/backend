package com.calai.backend.foodlog;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FoodLogEntityPatchTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void patch_foodName_shouldSet() throws Exception {
        FoodLogEntity e = new FoodLogEntity();
        e.setEffective(om.readTree("{\"foodName\":\"A\"}"));

        e.applyEffectivePatch("FOOD_NAME", new TextNode("B"));

        assertEquals("B", e.getEffective().get("foodName").asText());
    }

    @Test
    void patch_nutrients_shouldMerge() throws Exception {
        FoodLogEntity e = new FoodLogEntity();
        e.setEffective(om.readTree("{\"nutrients\":{\"kcal\":100,\"protein\":10}}"));

        // 只更新 kcal
        e.applyEffectivePatch("NUTRIENTS", om.readTree("{\"kcal\":200}"));

        assertEquals(200, e.getEffective().get("nutrients").get("kcal").asInt());
        assertEquals(10, e.getEffective().get("nutrients").get("protein").asInt()); // ✅ 保留
    }

    @Test
    void patch_healthScore_shouldSet() {
        FoodLogEntity e = new FoodLogEntity();
        e.applyEffectivePatch("HEALTH_SCORE", new IntNode(7));
        assertEquals(7, e.getEffective().get("healthScore").asInt());
    }

    @Test
    void nutrients_merge_shouldKeepExistingFields() throws Exception {
        FoodLogEntity e = new FoodLogEntity();
        e.setEffective(om.readTree("{\"nutrients\":{\"kcal\":100,\"protein\":10}}"));

        e.applyEffectivePatch("NUTRIENTS", om.readTree("{\"kcal\":200}"));

        assertEquals(200, e.getEffective().get("nutrients").get("kcal").asInt());
        assertEquals(10, e.getEffective().get("nutrients").get("protein").asInt()); // ✅ 保留
    }
}
