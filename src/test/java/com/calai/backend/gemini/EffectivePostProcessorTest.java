package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.task.HealthScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class EffectivePostProcessorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final HealthScore healthScore = new HealthScore();
    private final EffectivePostProcessor pp = new EffectivePostProcessor(healthScore);

    @Test
    void apply_should_add_healthScoreMeta_and_score_when_food() throws Exception {
        HealthScore v1 = new HealthScore();
        EffectivePostProcessor p = new EffectivePostProcessor(v1);

        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName":"White Bread",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":75,"protein":2.5,"fat":1,"carbs":14,"fiber":0.8,"sugar":1.5,"sodium":140},
            "confidence":0.9
          }
        """);

        ObjectNode out = p.apply(eff, "GEMINI");

        // meta
        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
        assertThat(out.get("healthScoreMeta").get("provider").asText()).isEqualTo("GEMINI");

        // score (可能因規則不同而變動，但必須在 1..10)
        if (out.get("healthScore") != null && out.get("healthScore").isInt()) {
            assertThat(out.get("healthScore").asInt()).isBetween(1, 10);
        }
    }

    @Test
    void apply_should_mark_non_food_and_cap_confidence() throws Exception {
        HealthScore v1 = new HealthScore();
        EffectivePostProcessor p = new EffectivePostProcessor(v1);

        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName":"Empty glass mug",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":0,"protein":0,"fat":0,"carbs":0,"fiber":0,"sugar":0,"sodium":0},
            "confidence":1.0
          }
        """);

        ObjectNode out = p.apply(eff, "GEMINI");

        // warnings contains NON_FOOD_SUSPECT
        assertThat(out.get("warnings")).isNotNull();
        assertThat(out.get("warnings").isArray()).isTrue();
        assertThat(out.get("warnings").toString()).contains("NON_FOOD_SUSPECT");

        // confidence capped to <= 0.3
        assertThat(out.get("confidence").asDouble()).isLessThanOrEqualTo(0.3d);

        // healthScore removed
        assertThat(out.get("healthScore")).isNull();

        // meta exists
        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
    }

    @Test
    void nonFood_should_add_warning_and_clamp_confidence_and_remove_healthScore() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName":"Empty glass mug",
            "confidence": 0.95,
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":0,"protein":0,"fat":0,"carbs":0,"fiber":0,"sugar":0,"sodium":0}
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI");

        assertThat(out.get("confidence").asDouble()).isEqualTo(0.3);
        assertThat(out.get("healthScore")).isNull(); // 被 remove
        assertThat(out.get("warnings").toString()).contains("NON_FOOD_SUSPECT");
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
    }

    @Test
    void normalFood_should_have_healthScore() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName":"Toast",
            "confidence": 0.8,
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":75,"protein":2.5,"fat":1,"carbs":14,"fiber":0.8,"sugar":1.5,"sodium":140}
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI");

        assertThat(out.get("healthScore").asInt()).isBetween(1, 10);
        assertThat(out.get("healthScoreMeta").get("provider").asText()).isEqualTo("GEMINI");
    }
}
