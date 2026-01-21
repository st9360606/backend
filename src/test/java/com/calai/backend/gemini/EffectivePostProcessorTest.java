package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.task.HealthScore;
import com.calai.backend.foodlog.task.NutritionSanityChecker; // ✅ 你新增的 checker
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class EffectivePostProcessorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final HealthScore healthScore = new HealthScore();

    // ✅ 若你的 NutritionSanityChecker 需要參數，改成 new NutritionSanityChecker(om) 或用 stub
    private final NutritionSanityChecker sanity = new NutritionSanityChecker();

    private final EffectivePostProcessor pp = new EffectivePostProcessor(healthScore, sanity);

    @Test
    void apply_should_add_healthScoreMeta_and_score_when_food() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName":"White Bread",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":75,"protein":2.5,"fat":1,"carbs":14,"fiber":0.8,"sugar":1.5,"sodium":140},
            "confidence":0.9
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI");

        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
        assertThat(out.get("healthScoreMeta").get("provider").asText()).isEqualTo("GEMINI");

        assertThat(out.get("healthScore")).isNotNull();
        assertThat(out.get("healthScore").asInt()).isBetween(1, 10);
    }

    @Test
    void apply_should_mark_non_food_and_cap_confidence() throws Exception {
        // ✅ 用 warnings=NO_FOOD_DETECTED 觸發 non-food，最穩
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName": null,
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":0,"protein":0,"fat":0,"carbs":0,"fiber":0,"sugar":0,"sodium":0},
            "confidence":0.1,
            "warnings":["NO_FOOD_DETECTED"]
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI");

        assertThat(out.get("warnings")).isNotNull();
        assertThat(out.get("warnings").toString()).contains("NON_FOOD_SUSPECT");

        assertThat(out.get("confidence").asDouble()).isLessThanOrEqualTo(0.3d);
        assertThat(out.get("healthScore")).isNull();

        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
    }
}
