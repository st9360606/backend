package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.task.NutritionSanityChecker;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class EffectivePostProcessorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final NutritionSanityChecker sanity = new NutritionSanityChecker();
    private final EffectivePostProcessor pp = new EffectivePostProcessor(sanity);

    @Test
    void apply_should_keep_healthScore_and_add_healthScoreMeta_when_healthScore_present() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName":"White Bread",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":75,"protein":2.5,"fat":1,"carbs":14,"fiber":0.8,"sugar":1.5,"sodium":140},
            "confidence":0.9,
            "healthScore": 7
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI", "PHOTO");

        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
        assertThat(out.get("healthScoreMeta").get("provider").asText()).isEqualTo("GEMINI");

        assertThat(out.get("healthScore")).isNotNull();
        assertThat(out.get("healthScore").asInt()).isEqualTo(7);
    }

    @Test
    void apply_should_remove_healthScore_when_input_healthScore_missing() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName":"White Bread",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":75,"protein":2.5,"fat":1,"carbs":14,"fiber":0.8,"sugar":1.5,"sodium":140},
            "confidence":0.9
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI", "PHOTO");

        assertThat(out.get("healthScore")).isNull();
        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("provider").asText()).isEqualTo("GEMINI");
    }

    @Test
    void apply_when_no_food_detected_should_set_degraded_reason_and_not_add_non_food_suspect() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName": null,
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":null,"protein":null,"fat":null,"carbs":null,"fiber":null,"sugar":null,"sodium":null},
            "confidence":0.1,
            "warnings":["NO_FOOD_DETECTED"]
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI", "PHOTO");

        assertThat(out.get("warnings")).isNotNull();
        String w = out.get("warnings").toString();
        assertThat(w).contains(FoodLogWarning.NO_FOOD_DETECTED.name());
        assertThat(w).contains(FoodLogWarning.LOW_CONFIDENCE.name());
        assertThat(w).doesNotContain(FoodLogWarning.NON_FOOD_SUSPECT.name());

        assertThat(out.path("aiMeta").path("degradedReason").asText()).isEqualTo("NO_FOOD");
        assertThat(out.path("aiMeta").path("degradedAtUtc").asText()).isNotBlank();

        assertThat(out.get("healthScore")).isNull();

        assertThat(out.get("healthScoreMeta")).isNotNull();
        assertThat(out.get("healthScoreMeta").get("version").asText()).isEqualTo("v1");
    }

    @Test
    void apply_should_use_actual_provider_as_meta_source() {
        NutritionSanityChecker checker = new NutritionSanityChecker();
        EffectivePostProcessor processor = new EffectivePostProcessor(checker);

        ObjectNode eff = JsonNodeFactory.instance.objectNode();
        eff.put("foodName", "Test");

        ObjectNode n = eff.putObject("nutrients");
        n.put("kcal", 100.0);
        n.put("protein", 10.0);
        n.put("fat", 5.0);
        n.put("carbs", 12.0);
        n.put("fiber", 1.0);
        n.put("sugar", 2.0);
        n.put("sodium", 100.0);

        eff.put("confidence", 0.98);

        ObjectNode out = processor.apply(eff, "OPENFOODFACTS", "BARCODE");

        assertThat(out.path("healthScoreMeta").path("provider").asText()).isEqualTo("OPENFOODFACTS");
        assertThat(out.path("healthScoreMeta").path("confidenceSource").asText()).isEqualTo("OPENFOODFACTS");
    }

    @Test
    void apply_when_label_has_no_food_detected_should_map_to_no_label() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
          {
            "foodName": "Nutrition facts label",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":0,"protein":0,"fat":0,"carbs":0,"fiber":0,"sugar":0,"sodium":0},
            "confidence":0.0,
            "warnings":["NO_FOOD_DETECTED","NO_LABEL_DETECTED","LOW_CONFIDENCE"]
          }
        """);

        ObjectNode out = pp.apply(eff, "GEMINI", "LABEL");

        assertThat(out.path("aiMeta").path("degradedReason").asText()).isEqualTo("NO_LABEL");
        assertThat(out.path("warnings").toString()).contains(FoodLogWarning.NO_LABEL_DETECTED.name());
    }
}
