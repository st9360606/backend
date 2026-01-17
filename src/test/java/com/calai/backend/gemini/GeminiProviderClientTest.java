package com.calai.backend.gemini;

import com.calai.backend.foodlog.provider.GeminiProperties;
import com.calai.backend.foodlog.provider.GeminiProviderClient;
import com.calai.backend.foodlog.service.HealthScoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class GeminiProviderClientTest {

    private final ObjectMapper om = new ObjectMapper();
    private final HealthScoreService hs = new HealthScoreService();

    @Test
    void normalize_ok_and_healthScore_in_range() throws Exception {
        GeminiProperties props = new GeminiProperties();
        props.setApiKey("dummy"); // 不會用到
        var client = new GeminiProviderClient(null, props, hs, om);

        var raw = om.readTree("""
          {
            "foodName":"Chicken Salad",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":350,"protein":25,"fat":18,"carbs":20,"fiber":6,"sugar":5,"sodium":450},
            "confidence":0.72
          }
        """);

        // 直接呼叫 private 會不方便：你可以把 normalizeToEffective / parse 抽成 package-private helper
        // 這裡示範：用反射（或你把 normalizeToEffective 改成 package-private）
        var m = GeminiProviderClient.class.getDeclaredMethod("normalizeToEffective", com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);

        ObjectNode eff = (ObjectNode) m.invoke(client, raw);

        assertThat(eff.get("nutrients").get("fiber").asDouble()).isEqualTo(6d);

        Integer score = hs.score(6d, 5d, 450d);
        assertThat(score).isBetween(1, 10);
    }

    @Test
    void normalize_negative_number_should_fail() throws Exception {
        GeminiProperties props = new GeminiProperties();
        props.setApiKey("dummy");
        var client = new GeminiProviderClient(null, props, hs, om);

        var raw = om.readTree("""
          {
            "foodName":"X",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":-1,"protein":1,"fat":1,"carbs":1}
          }
        """);

        var m = GeminiProviderClient.class.getDeclaredMethod("normalizeToEffective", com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);

        assertThatThrownBy(() -> m.invoke(client, raw))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
