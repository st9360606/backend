package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.HealthScore;
import com.calai.backend.foodlog.task.NutritionSanityChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class EffectivePostProcessorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final HealthScore healthScore = new HealthScore();
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
    void apply_when_no_food_detected_should_set_degraded_reason_and_not_add_non_food_suspect() throws Exception {
        // ✅ NO_FOOD_DETECTED：新規則是「降級優先」，不混 NON_FOOD_SUSPECT
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName": null,
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":null,"protein":null,"fat":null,"carbs":null,"fiber":null,"sugar":null,"sodium":null},
            "confidence":0.1,
            "warnings":["NO_FOOD_DETECTED"]
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI");

        // ✅ warnings：保留 NO_FOOD_DETECTED + LOW_CONFIDENCE（你原規則）
        assertThat(out.get("warnings")).isNotNull();
        String w = out.get("warnings").toString();
        assertThat(w).contains(FoodLogWarning.NO_FOOD_DETECTED.name());
        assertThat(w).contains(FoodLogWarning.LOW_CONFIDENCE.name());

        // ✅ 關鍵：不再追加 NON_FOOD_SUSPECT（避免 UI 混亂）
        assertThat(w).doesNotContain(FoodLogWarning.NON_FOOD_SUSPECT.name());

        // ✅ degradedReason 寫入 aiMeta
        assertThat(out.path("aiMeta").path("degradedReason").asText()).isEqualTo("NO_FOOD");
        assertThat(out.path("aiMeta").path("degradedAtUtc").asText()).isNotBlank();

        // ✅ 降級不計分
        assertThat(out.get("healthScore")).isNull();

        // ✅ healthScoreMeta 仍會存在（方便 trace）
        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
    }
}
