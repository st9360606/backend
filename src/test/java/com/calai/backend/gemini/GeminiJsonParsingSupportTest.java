package com.calai.backend.foodlog;

import com.calai.backend.foodlog.provider.gemini.support.GeminiJsonParsingSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GeminiJsonParsingSupportTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static Object invokePrivateStatic(Class<?> owner, String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = owner.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("invokePrivateStatic failed: " + owner.getSimpleName() + "." + methodName, e);
        }
    }

    @Test
    void balanceJsonIfNeeded_should_close_brackets_then_braces_in_nested_case() throws Exception {
        String s = "{\"a\":[{\"b\":1}";
        String fixed = (String) invokePrivateStatic(
                GeminiJsonParsingSupport.class,
                "balanceJsonIfNeeded",
                new Class[]{String.class},
                s
        );

        assertThat(fixed).endsWith("]}");
        assertThatCode(() -> OM.readTree(fixed)).doesNotThrowAnyException();

        JsonNode node = OM.readTree(fixed);
        assertThat(node.get("a").isArray()).isTrue();
        assertThat(node.get("a").get(0).get("b").asInt()).isEqualTo(1);
    }

    @Test
    void patchDanglingTail_should_fill_null_for_value_colon() {
        String s = "{\"quantity\":{\"value\":";
        String fixed = (String) invokePrivateStatic(
                GeminiJsonParsingSupport.class,
                "patchDanglingTail",
                new Class[]{String.class},
                s
        );

        assertThat(fixed).endsWith("null");
    }

    @Test
    void tryParseJson_should_parse_truncated_unit_colon_as_null() {
        GeminiJsonParsingSupport support = new GeminiJsonParsingSupport(OM);

        String raw = "{\"quantity\":{\"unit\":";

        JsonNode parsed = support.tryParseJson(raw);

        assertThat(parsed).isNotNull();
        assertThat(parsed.get("quantity").get("unit").isNull()).isTrue();
    }

    @Test
    void patchDanglingTail_should_fill_null_for_unit_and_basis() {
        String unit = "{\"quantity\":{\"unit\":";
        String unitFixed = (String) invokePrivateStatic(
                GeminiJsonParsingSupport.class,
                "patchDanglingTail",
                new Class[]{String.class},
                unit
        );
        assertThat(unitFixed).endsWith("null");

        String basis = "{\"labelMeta\":{\"basis\":";
        String basisFixed = (String) invokePrivateStatic(
                GeminiJsonParsingSupport.class,
                "patchDanglingTail",
                new Class[]{String.class},
                basis
        );
        assertThat(basisFixed).endsWith("null");
    }

    @Test
    void removeTrailingCommas_should_make_object_and_array_parseable() throws Exception {
        String obj = "{\"a\":1,}";
        String objFixed = (String) invokePrivateStatic(
                GeminiJsonParsingSupport.class,
                "removeTrailingCommas",
                new Class[]{String.class},
                obj
        );
        assertThat(objFixed).isEqualTo("{\"a\":1}");

        String arr = "[1,2,]";
        String arrFixed = (String) invokePrivateStatic(
                GeminiJsonParsingSupport.class,
                "removeTrailingCommas",
                new Class[]{String.class},
                arr
        );
        assertThat(arrFixed).isEqualTo("[1,2]");

        assertThatCode(() -> OM.readTree(objFixed)).doesNotThrowAnyException();
        assertThatCode(() -> OM.readTree(arrFixed)).doesNotThrowAnyException();
    }

    @Test
    void tryParseJson_should_parse_truncated_quantity_value_colon() {
        GeminiJsonParsingSupport support = new GeminiJsonParsingSupport(OM);

        String raw = "{\"foodName\":\"x\",\"quantity\":{\"value\":";

        JsonNode parsed = support.tryParseJson(raw);

        assertThat(parsed).isNotNull();
        assertThat(parsed.get("foodName").asText()).isEqualTo("x");
        assertThat(parsed.get("quantity").get("value").isNull()).isTrue();
    }
}