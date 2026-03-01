package com.calai.backend.foodlog;

import com.calai.backend.foodlog.provider.GeminiEffectiveJsonSupport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiEffectiveJsonSupportTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void finalizeEffective_should_scale_nutrients_and_set_quantity_to_whole_package() {
        // raw：labelMeta 指示 PER_SERVING + 10 份
        ObjectNode raw = OM.createObjectNode();
        ObjectNode lm = raw.putObject("labelMeta");
        lm.put("servingsPerContainer", 10);
        lm.put("basis", "PER_SERVING");

        // effective：目前是每份 sodium=13，quantity=125ML
        ObjectNode effective = OM.createObjectNode();

        ObjectNode q = effective.putObject("quantity");
        q.put("value", 125.0);
        q.put("unit", "ML");

        ObjectNode n = effective.putObject("nutrients");
        n.put("sodium", 13.0);
        n.put("kcal", 0.0);
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");

        // ✅ 不直接打 private helper，改測 public finalizeEffective
        GeminiEffectiveJsonSupport.finalizeEffective(true, raw, effective);

        assertThat(effective.get("nutrients").get("sodium").asDouble()).isEqualTo(130.0);
        assertThat(effective.get("quantity").get("value").asDouble()).isEqualTo(1.0);
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("SERVING");
    }

    @Test
    void normalizeToEffective_should_keep_empty_warnings_array() {
        ObjectNode raw = OM.createObjectNode();
        raw.put("foodName", "御茶園 台灣四季春");

        ObjectNode q = raw.putObject("quantity");
        q.put("value", 1.0);
        q.put("unit", "SERVING");

        ObjectNode n = raw.putObject("nutrients");
        n.put("kcal", 0.0);
        n.put("protein", 0.0);
        n.put("fat", 0.0);
        n.put("carbs", 0.0);
        n.put("fiber", 0.0);
        n.put("sugar", 0.0);
        n.put("sodium", 130.0);

        raw.put("confidence", 0.25);
        raw.putArray("warnings");

        ObjectNode eff = GeminiEffectiveJsonSupport.normalizeToEffective(raw);

        assertThat(eff.has("warnings")).isTrue();
        assertThat(eff.get("warnings").isArray()).isTrue();
        assertThat(eff.get("warnings").size()).isEqualTo(0);
    }
}