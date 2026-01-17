package com.calai.backend.gemini;

import com.calai.backend.foodlog.provider.GeminiProperties;
import com.calai.backend.foodlog.provider.GeminiProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class GeminiProviderClientTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void normalize_ok_should_keep_nutrients() throws Exception {
        GeminiProperties props = new GeminiProperties();
        props.setApiKey("dummy"); // 不會用到（因為不打 http）
        GeminiProviderClient client = new GeminiProviderClient(null, props, om);

        var raw = om.readTree("""
          {
            "foodName":"Chicken Salad",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":350,"protein":25,"fat":18,"carbs":20,"fiber":6,"sugar":5,"sodium":450},
            "confidence":0.72
          }
        """);

        var m = GeminiProviderClient.class.getDeclaredMethod(
                "normalizeToEffective",
                com.fasterxml.jackson.databind.JsonNode.class
        );
        m.setAccessible(true);

        ObjectNode eff = (ObjectNode) m.invoke(client, raw);

        assertThat(eff.get("foodName").asText()).isEqualTo("Chicken Salad");
        assertThat(eff.get("quantity").get("unit").asText()).isEqualTo("SERVING");
        assertThat(eff.get("nutrients").get("fiber").asDouble()).isEqualTo(6d);
        assertThat(eff.get("confidence").asDouble()).isEqualTo(0.72d);
    }

    @Test
    void normalize_negative_number_should_fail() throws Exception {
        GeminiProperties props = new GeminiProperties();
        props.setApiKey("dummy");
        GeminiProviderClient client = new GeminiProviderClient(null, props, om);

        var raw = om.readTree("""
          {
            "foodName":"X",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":-1,"protein":1,"fat":1,"carbs":1}
          }
        """);

        var m = GeminiProviderClient.class.getDeclaredMethod(
                "normalizeToEffective",
                com.fasterxml.jackson.databind.JsonNode.class
        );
        m.setAccessible(true);

        assertThatThrownBy(() -> m.invoke(client, raw))
                // reflection 會包 InvocationTargetException
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
