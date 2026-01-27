package com.calai.backend.foodlog;

import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EffectivePostProcessorDegradeTest extends MySqlContainerBaseTest {

    @Autowired
    EffectivePostProcessor postProcessor;
    @Autowired ObjectMapper om;

    @Test
    void unknown_food_should_not_add_non_food_suspect() {
        ObjectNode eff = baseEff(0.2);
        eff.putArray("warnings").add(FoodLogWarning.UNKNOWN_FOOD.name());

        ObjectNode out = postProcessor.apply(eff, "GEMINI");

        assertThat(out.path("aiMeta").path("degradedReason").asText()).isEqualTo("UNKNOWN_FOOD");

        // warnings 應包含 UNKNOWN_FOOD / LOW_CONFIDENCE（視你規則 conf<=0.4）
        assertThat(warnings(out)).contains(FoodLogWarning.UNKNOWN_FOOD.name());
        assertThat(warnings(out)).contains(FoodLogWarning.LOW_CONFIDENCE.name());

        // ✅ 關鍵：降級時不應混入 NON_FOOD_SUSPECT（避免 UI 混亂）
        assertThat(warnings(out)).doesNotContain(FoodLogWarning.NON_FOOD_SUSPECT.name());

        // healthScore 應該沒有（降級不計分）
        assertThat(out.get("healthScore")).isNull();
    }

    @Test
    void no_food_should_not_add_non_food_suspect() {
        ObjectNode eff = baseEff(0.1);
        eff.putArray("warnings").add(FoodLogWarning.NO_FOOD_DETECTED.name());

        ObjectNode out = postProcessor.apply(eff, "GEMINI");

        assertThat(out.path("aiMeta").path("degradedReason").asText()).isEqualTo("NO_FOOD");

        assertThat(warnings(out)).contains(FoodLogWarning.NO_FOOD_DETECTED.name());
        assertThat(warnings(out)).contains(FoodLogWarning.LOW_CONFIDENCE.name());
        assertThat(warnings(out)).doesNotContain(FoodLogWarning.NON_FOOD_SUSPECT.name());

        assertThat(out.get("healthScore")).isNull();
    }

    private ObjectNode baseEff(double conf) {
        ObjectNode eff = om.createObjectNode();

        // quantity
        ObjectNode q = eff.putObject("quantity");
        q.put("value", 1.0);
        q.put("unit", "SERVING");

        // nutrients（全部 null，符合 UNKNOWN/NO_FOOD 常見型態）
        ObjectNode n = eff.putObject("nutrients");
        n.putNull("kcal");
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        n.putNull("sodium");

        eff.put("confidence", conf);
        return eff;
    }

    private java.util.List<String> warnings(ObjectNode eff) {
        ArrayNode w = (ArrayNode) eff.get("warnings");
        if (w == null) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        w.forEach(x -> out.add(x.asText()));
        return out;
    }
}
