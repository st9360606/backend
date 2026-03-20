package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.provider.gemini.support.GeminiEffectiveJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiEffectiveJsonSupportTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void finalizeEffective_should_scale_per_serving_to_whole_package_when_servings_gt_1() {
        ObjectNode raw = om.createObjectNode();
        raw.put("foodName", "Bourbon Chocoliere");

        ObjectNode quantity = raw.putObject("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = raw.putObject("nutrients");
        nutrients.put("kcal", 100.0);
        nutrients.put("protein", 2.0);
        nutrients.put("fat", 3.0);
        nutrients.put("carbs", 10.0);
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");
        nutrients.putNull("sodium");

        raw.put("confidence", 0.9);
        raw.putArray("warnings");

        ObjectNode labelMeta = raw.putObject("labelMeta");
        labelMeta.put("servingsPerContainer", 5.0);
        labelMeta.put("basis", "PER_SERVING");

        ObjectNode effective = GeminiEffectiveJsonSupport.normalizeToEffective(raw);
        GeminiEffectiveJsonSupport.finalizeEffective(raw, effective);

        assertThat(effective.path("nutrients").path("kcal").asDouble()).isEqualTo(500.0);
        assertThat(effective.path("nutrients").path("protein").asDouble()).isEqualTo(10.0);
        assertThat(effective.path("labelMeta").path("basis").asText()).isEqualTo("WHOLE_PACKAGE");
        assertThat(effective.path("labelMeta").path("servingsPerContainer").asDouble()).isEqualTo(5.0);
        assertThat(effective.path("quantity").path("value").asDouble()).isEqualTo(1.0);
        assertThat(effective.path("quantity").path("unit").asText()).isEqualTo("SERVING");
    }

    @Test
    void finalizeEffective_should_canonicalize_single_serve_packaged_to_whole_package() {
        ObjectNode raw = om.createObjectNode();
        raw.put("foodName", "Coca-Cola Zero");

        ObjectNode quantity = raw.putObject("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = raw.putObject("nutrients");
        nutrients.put("kcal", 0.0);
        nutrients.put("protein", 0.0);
        nutrients.put("fat", 0.0);
        nutrients.put("carbs", 0.0);
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");
        nutrients.putNull("sodium");

        raw.put("confidence", 0.95);
        raw.putArray("warnings");

        ObjectNode labelMeta = raw.putObject("labelMeta");
        labelMeta.put("servingsPerContainer", 1.0);
        labelMeta.put("basis", "PER_SERVING");

        ObjectNode effective = GeminiEffectiveJsonSupport.normalizeToEffective(raw);
        GeminiEffectiveJsonSupport.finalizeEffective(raw, effective);

        assertThat(effective.path("labelMeta").path("basis").asText()).isEqualTo("PER_SERVING");
        assertThat(effective.path("labelMeta").path("servingsPerContainer").asDouble()).isEqualTo(1.0);
        assertThat(effective.path("quantity").path("value").asDouble()).isEqualTo(1.0);
        assertThat(effective.path("quantity").path("unit").asText()).isEqualTo("SERVING");
        assertThat(effective.path("nutrients").path("kcal").asDouble()).isEqualTo(0.0);
    }
}