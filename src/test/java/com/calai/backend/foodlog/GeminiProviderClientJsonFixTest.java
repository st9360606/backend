package com.calai.backend.foodlog;

import com.calai.backend.foodlog.provider.GeminiProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
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

    private static GeminiProviderClient newClientForParsing() {
        // normalizeToEffective 不用到 props/http/telemetry
        // tryParseJson 需要 ObjectMapper（你 class 內用 field om）
        return new GeminiProviderClient(null, null, OM, null);
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
