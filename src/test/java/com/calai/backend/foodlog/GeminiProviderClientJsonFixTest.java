package com.calai.backend.foodlog;

import com.calai.backend.foodlog.provider.GeminiProviderClient;
import com.calai.backend.foodlog.provider.GeminiProperties;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GeminiProviderClientJsonFixTest {

    private static final ObjectMapper OM = new ObjectMapper();

    // ========= reflection helpers =========

    private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = GeminiProviderClient.class.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("invokeStatic failed: " + methodName, e);
        }
    }

    private static Object invokeInstance(Object target, String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException("invokeInstance failed: " + methodName, e);
        }
    }

    /**
     * ✅ 不依賴 GeminiProviderClient 的 constructor 參數數量。
     * 會自動找「參數最多」的 constructor，並依參數型別填入：
     * - ObjectMapper -> OM
     * - GeminiProperties -> null（這支測試不需要）
     * - ProviderTelemetry -> null（這支測試不需要）
     * - 其他全部塞 null / primitive 預設值
     */
    private static GeminiProviderClient newClientForParsing() {
        try {
            Constructor<?> ctor = Arrays.stream(GeminiProviderClient.class.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();

            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();

            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];

                if (ObjectMapper.class.isAssignableFrom(t)) {
                    args[i] = OM;
                } else if (GeminiProperties.class.isAssignableFrom(t)) {
                    args[i] = null;
                } else if (ProviderTelemetry.class.isAssignableFrom(t)) {
                    args[i] = null;
                } else if (t.isPrimitive()) {
                    args[i] = primitiveDefault(t);
                } else {
                    args[i] = null;
                }
            }

            ctor.setAccessible(true);
            return (GeminiProviderClient) ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("newClientForParsing failed", e);
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

    // ========= tests =========

    @Test
    void balanceJsonIfNeeded_should_close_brackets_then_braces_in_nested_case() throws Exception {
        String s = "{\"a\":[{\"b\":1}";
        String fixed = (String) invokeStatic("balanceJsonIfNeeded", new Class[]{String.class}, s);

        // 1) 結尾順序要先 ] 再 }
        assertThat(fixed).endsWith("]}");

        // 2) 也要能 parse
        assertThatCode(() -> OM.readTree(fixed)).doesNotThrowAnyException();

        JsonNode node = OM.readTree(fixed);
        assertThat(node.get("a").isArray()).isTrue();
        assertThat(node.get("a").get(0).get("b").asInt()).isEqualTo(1);
    }

    @Test
    void patchDanglingTail_should_fill_null_for_value_colon() {
        String s = "{\"quantity\":{\"value\":";
        String fixed = (String) invokeStatic("patchDanglingTail", new Class[]{String.class}, s);

        // "value": 這種 key → 補 null
        assertThat(fixed).endsWith("null");
    }

    @Test
    void patchDanglingTail_should_fill_defaults_for_unit_and_basis() {
        String unit = "{\"quantity\":{\"unit\":";
        String unitFixed = (String) invokeStatic("patchDanglingTail", new Class[]{String.class}, unit);
        assertThat(unitFixed).endsWith("\"SERVING\"");

        String basis = "{\"labelMeta\":{\"basis\":";
        String basisFixed = (String) invokeStatic("patchDanglingTail", new Class[]{String.class}, basis);
        assertThat(basisFixed).endsWith("\"PER_SERVING\"");
    }

    @Test
    void removeTrailingCommas_should_make_object_and_array_parseable() throws Exception {
        String obj = "{\"a\":1,}";
        String objFixed = (String) invokeStatic("removeTrailingCommas", new Class[]{String.class}, obj);
        assertThat(objFixed).isEqualTo("{\"a\":1}");

        String arr = "[1,2,]";
        String arrFixed = (String) invokeStatic("removeTrailingCommas", new Class[]{String.class}, arr);
        assertThat(arrFixed).isEqualTo("[1,2]");

        assertThatCode(() -> OM.readTree(objFixed)).doesNotThrowAnyException();
        assertThatCode(() -> OM.readTree(arrFixed)).doesNotThrowAnyException();
    }

    @Test
    void tryParseJson_should_parse_truncated_quantity_value_colon() {
        GeminiProviderClient client = newClientForParsing();

        // 模擬你 log 的截斷：value: 後面沒東西
        String raw = "{\"foodName\":\"x\",\"quantity\":{\"value\":";

        JsonNode parsed = (JsonNode) invokeInstance(client, "tryParseJson", new Class[]{String.class}, raw);
        assertThat(parsed).isNotNull();
        assertThat(parsed.get("foodName").asText()).isEqualTo("x");

        // patchDanglingTail 會把 value 補成 null
        assertThat(parsed.get("quantity").get("value").isNull()).isTrue();
    }

    @Test
    void applyWholePackageScalingIfNeeded_should_scale_nutrients_and_set_quantity_to_whole_package() {
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
        n.put("kcal", 0.0); // 也會被乘（0*10 仍是 0）
        n.putNull("protein"); // null 不乘
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");

        invokeStatic(
                "applyWholePackageScalingIfNeeded",
                new Class[]{JsonNode.class, ObjectNode.class},
                raw,
                effective
        );

        assertThat(effective.get("nutrients").get("sodium").asDouble()).isEqualTo(130.0);

        // quantity 應轉成整包：1 SERVING
        assertThat(effective.get("quantity").get("value").asDouble()).isEqualTo(1.0);
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("SERVING");
    }

    @Test
    void normalizeToEffective_should_keep_empty_warnings_array() {
        GeminiProviderClient client = newClientForParsing();

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

        // 空 warnings
        raw.putArray("warnings");

        ObjectNode eff = (ObjectNode) invokeInstance(client, "normalizeToEffective", new Class[]{JsonNode.class}, raw);

        assertThat(eff.has("warnings")).isTrue();
        assertThat(eff.get("warnings").isArray()).isTrue();
        assertThat(eff.get("warnings").size()).isEqualTo(0);
    }
}