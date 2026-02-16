package com.calai.backend.gemini;

import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.provider.GeminiProviderClient;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.*;

public class GeminiProviderClientTest2 {

    private final ObjectMapper om = new ObjectMapper();

    /**
     * ✅ 不依賴 GeminiProviderClient 的 constructor 參數數量。
     * 自動找「參數最多」constructor，並依型別配對：
     * - ObjectMapper -> om
     * - GeminiProperties -> props
     * - ProviderTelemetry -> telemetry
     * - 其他塞 null / primitive 預設值
     */
    private GeminiProviderClient newClient(GeminiProperties props, ProviderTelemetry telemetry) {
        try {
            Constructor<?> ctor = Arrays.stream(GeminiProviderClient.class.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();

            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();

            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];

                if (ObjectMapper.class.isAssignableFrom(t)) {
                    args[i] = om;
                } else if (GeminiProperties.class.isAssignableFrom(t)) {
                    args[i] = props;
                } else if (ProviderTelemetry.class.isAssignableFrom(t)) {
                    args[i] = telemetry;
                } else if (t.isPrimitive()) {
                    args[i] = primitiveDefault(t);
                } else {
                    args[i] = null;
                }
            }

            ctor.setAccessible(true);
            return (GeminiProviderClient) ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("newClient failed", e);
        }
    }

    private static Object primitiveDefault(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == char.class) return (char) 0;
        return 0;
    }

    @Test
    void normalize_ok_should_keep_nutrients() throws Exception {
        GeminiProperties props = new GeminiProperties();
        props.setApiKey("dummy"); // 不會用到（因為不打 http）

        ProviderTelemetry telemetry = new ProviderTelemetry();
        GeminiProviderClient client = newClient(props, telemetry);

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

    /**
     * ✅ 目前 normalizeToEffective 的行為：
     * - nutrients 出現負數，不會 throw
     * - 會把該欄位轉成 null（sanitized）
     */
    @Test
    void normalize_negative_number_should_be_sanitized_to_null() throws Exception {
        GeminiProperties props = new GeminiProperties();
        props.setApiKey("dummy");

        ProviderTelemetry telemetry = new ProviderTelemetry();
        GeminiProviderClient client = newClient(props, telemetry);

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

        ObjectNode eff = (ObjectNode) m.invoke(client, raw);

        // kcal 負數 → 被 sanitize 成 null
        assertThat(eff.get("nutrients").get("kcal").isNull()).isTrue();

        // 其他合法值仍保留
        assertThat(eff.get("nutrients").get("protein").asDouble()).isEqualTo(1d);
        assertThat(eff.get("nutrients").get("fat").asDouble()).isEqualTo(1d);
        assertThat(eff.get("nutrients").get("carbs").asDouble()).isEqualTo(1d);

        // confidence 未提供 → normalizeToEffective 會輸出 null
        assertThat(eff.get("confidence").isNull()).isTrue();
    }
}