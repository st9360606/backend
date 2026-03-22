package com.calai.backend.foodlog.provider.gemini.label;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.provider.gemini.config.GeminiProperties;
import com.calai.backend.foodlog.provider.gemini.support.GeminiJsonParsingSupport;
import com.calai.backend.foodlog.provider.gemini.transport.GeminiTransportSupport;
import com.calai.backend.foodlog.provider.prompt.GeminiPromptFactory;
import com.calai.backend.foodlog.provider.routing.AiModelTierRouter;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.provider.support.ProviderTelemetry;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiLabelProcessorTest {

    private final ObjectMapper om = new ObjectMapper();

    private GeminiTransportSupport transportSupport;
    private GeminiJsonParsingSupport jsonParsingSupport;
    private GeminiPromptFactory promptFactory;
    private ProviderTelemetry telemetry;
    private AiModelTierRouter modelRouter;
    private StorageService storage;
    private GeminiProperties props;

    private GeminiLabelProcessor processor;

    @BeforeEach
    void setUp() {
        transportSupport = mock(GeminiTransportSupport.class);
        jsonParsingSupport = mock(GeminiJsonParsingSupport.class);
        promptFactory = mock(GeminiPromptFactory.class);
        telemetry = mock(ProviderTelemetry.class);
        modelRouter = mock(AiModelTierRouter.class);
        storage = mock(StorageService.class, RETURNS_DEEP_STUBS);

        props = new GeminiProperties();
        props.setApiKey("dummy");
        props.setEnabled(true);
        props.setLabelUseFunctionCalling(false);

        processor = newProcessor();
    }

    @Test
    @DisplayName("supports: LABEL 應回 true，其餘 method 應回 false")
    void supports_should_match_label_only() {
        assertThat(processor.supports("LABEL")).isTrue();
        assertThat(processor.supports("label")).isTrue();

        assertThat(processor.supports("PHOTO")).isFalse();
        assertThat(processor.supports("ALBUM")).isFalse();
        assertThat(processor.supports("BARCODE")).isFalse();
        assertThat(processor.supports(null)).isFalse();
    }

    @Test
    @DisplayName("process: functionArgs 直接提供有效 JSON 時應回成功結果")
    void process_should_return_success_when_function_args_are_valid() throws Exception {
        FoodLogEntity entity = newEntity("label-log-1", "LABEL", "DG-0", "obj/label-1.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-label-test");
        when(modelRouter.resolveOrThrow(any(), any())).thenReturn(resolved);

        when(storage.open("obj/label-1.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(100);
        when(tok.candTok()).thenReturn(50);
        when(tok.totalTok()).thenReturn(150);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(om.readTree("""
            {
              "foodName":"Greek Yogurt",
              "quantity":{"value":1,"unit":"SERVING"},
              "nutrients":{
                "kcal":120,
                "protein":10,
                "fat":3,
                "carbs":12,
                "fiber":0,
                "sugar":10,
                "sodium":95
              },
              "confidence":0.92,
              "labelMeta":{"servingsPerContainer":2,"basis":"PER_SERVING"}
            }
            """));
        when(callResult.text()).thenReturn(null);
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(
                any(byte[].class),
                any(),
                any(),
                eq("gemini-label-test"),
                anyBoolean(),
                eq("label-log-1")
        )).thenReturn(callResult);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").asText()).isEqualTo("Greek Yogurt");
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        // 目前 production 行為：
        // PER_SERVING + servingsPerContainer=2 會在 finalizeEffective() 乘成整包
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(240.0d);
        assertThat(effective.get("nutrients").get("protein").asDouble()).isEqualTo(20.0d);
        assertThat(effective.get("nutrients").get("fat").asDouble()).isEqualTo(6.0d);
        assertThat(effective.get("nutrients").get("carbs").asDouble()).isEqualTo(24.0d);
        assertThat(effective.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("sugar").asDouble()).isEqualTo(20.0d);
        assertThat(effective.get("nutrients").get("sodium").asDouble()).isEqualTo(190.0d);

        assertThat(effective.get("confidence").asDouble()).isEqualTo(0.92d);
        assertThat(effective.get("labelMeta").get("servingsPerContainer").asDouble()).isEqualTo(2.0d);
        assertThat(effective.get("labelMeta").get("basis").asText()).isEqualTo("WHOLE_PACKAGE");

        verify(telemetry).ok(
                eq("GEMINI"),
                eq("gemini-label-test"),
                eq("label-log-1"),
                anyLong(),
                eq(100),
                eq(50),
                eq(150)
        );
    }

    @Test
    @DisplayName("process: 純文字無法解析時目前應 fallback 為 LABEL_PARTIAL")
    void process_should_fallback_label_partial_when_text_cannot_be_parsed() throws Exception {
        FoodLogEntity entity = newEntity("label-log-2", "LABEL", "DG-0", "obj/label-2.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-label-test");
        when(modelRouter.resolveOrThrow(any(), any())).thenReturn(resolved);

        when(storage.open("obj/label-2.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{9, 9}));

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(10);
        when(tok.candTok()).thenReturn(20);
        when(tok.totalTok()).thenReturn(30);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(null);
        when(callResult.text()).thenReturn("no label, too blurry");
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(
                any(byte[].class),
                any(),
                any(),
                eq("gemini-label-test"),
                anyBoolean(),
                eq("label-log-2")
        )).thenReturn(callResult);

        when(jsonParsingSupport.tryParseJson("no label, too blurry")).thenReturn(null);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        // 目前 production 行為：
        // 這個 case 會落到 LABEL_PARTIAL，而不是 NO_LABEL_DETECTED
        assertThat(effective.get("foodName").asText()).isEqualTo("Nutrition facts label");

        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("SERVING");
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("protein").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("fat").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("carbs").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("sugar").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("sodium").asDouble()).isEqualTo(0.0d);

        assertThat(effective.get("warnings").isArray()).isTrue();
        assertThat(asTextArray(effective.get("warnings"))).contains("LABEL_PARTIAL", "LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("process: 截斷 JSON-like 文字時應 fallback 為 LABEL_PARTIAL")
    void process_should_fallback_label_partial_for_truncated_started_json() throws Exception {
        FoodLogEntity entity = newEntity("label-log-3", "LABEL", "DG-0", "obj/label-3.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-label-test");
        when(modelRouter.resolveOrThrow(any(), any())).thenReturn(resolved);

        when(storage.open("obj/label-3.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{3, 3, 3}));

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(21);
        when(tok.candTok()).thenReturn(22);
        when(tok.totalTok()).thenReturn(43);

        String rawText = """
                {"foodName":"Chocolate Cookies"
                """;

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(null);
        when(callResult.text()).thenReturn(rawText);
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(
                any(byte[].class),
                any(),
                any(),
                eq("gemini-label-test"),
                anyBoolean(),
                eq("label-log-3")
        )).thenReturn(callResult);

        when(jsonParsingSupport.tryParseJson(rawText)).thenReturn(null);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").asText()).isEqualTo("Chocolate Cookies");
        assertThat(asTextArray(effective.get("warnings"))).contains("LABEL_PARTIAL", "LOW_CONFIDENCE");
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("process: broken JSON-like 可抽值時應回成功 nutrition 結果")
    void process_should_extract_nutrition_from_broken_json_like_text() throws Exception {
        FoodLogEntity entity = newEntity("label-log-4", "LABEL", "DG-0", "obj/label-4.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-label-test");
        when(modelRouter.resolveOrThrow(any(), any())).thenReturn(resolved);

        when(storage.open("obj/label-4.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{4, 4, 4}));

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(31);
        when(tok.candTok()).thenReturn(32);
        when(tok.totalTok()).thenReturn(63);

        String rawText = """
                "foodName":"Protein Bar",
                "value":1,
                "unit":"PACK",
                "kcal":220,
                "protein":20,
                "fat":7,
                "carbs":18,
                "sugar":9,
                "sodium":180,
                "confidence":0.87
                """;

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(null);
        when(callResult.text()).thenReturn(rawText);
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(
                any(byte[].class),
                any(),
                any(),
                eq("gemini-label-test"),
                anyBoolean(),
                eq("label-log-4")
        )).thenReturn(callResult);

        when(jsonParsingSupport.tryParseJson(rawText)).thenReturn(null);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").asText()).isEqualTo("Protein Bar");
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("PACK");
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(220.0d);
        assertThat(effective.get("nutrients").get("protein").asDouble()).isEqualTo(20.0d);
        assertThat(effective.get("confidence").asDouble()).isEqualTo(0.87d);
    }

    private GeminiLabelProcessor newProcessor() {
        try {
            Constructor<?> ctor = Arrays.stream(GeminiLabelProcessor.class.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();

            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();

            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];

                if (GeminiTransportSupport.class.isAssignableFrom(t)) {
                    args[i] = transportSupport;
                } else if (GeminiJsonParsingSupport.class.isAssignableFrom(t)) {
                    args[i] = jsonParsingSupport;
                } else if (GeminiPromptFactory.class.isAssignableFrom(t)) {
                    args[i] = promptFactory;
                } else if (ObjectMapper.class.isAssignableFrom(t)) {
                    args[i] = om;
                } else if (ProviderTelemetry.class.isAssignableFrom(t)) {
                    args[i] = telemetry;
                } else if (AiModelTierRouter.class.isAssignableFrom(t)) {
                    args[i] = modelRouter;
                } else if (GeminiLabelFallbackSupport.class.isAssignableFrom(t)) {
                    args[i] = new GeminiLabelFallbackSupport(om);
                } else if (GeminiProperties.class.isAssignableFrom(t)) {
                    args[i] = props;
                } else if (t == boolean.class) {
                    args[i] = false;
                } else if (t == byte.class) {
                    args[i] = (byte) 0;
                } else if (t == short.class) {
                    args[i] = (short) 0;
                } else if (t == int.class) {
                    args[i] = 0;
                } else if (t == long.class) {
                    args[i] = 0L;
                } else if (t == float.class) {
                    args[i] = 0f;
                } else if (t == double.class) {
                    args[i] = 0d;
                } else if (t == char.class) {
                    args[i] = (char) 0;
                } else {
                    args[i] = null;
                }
            }

            ctor.setAccessible(true);
            return (GeminiLabelProcessor) ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("newProcessor failed", e);
        }
    }

    private static FoodLogEntity newEntity(
            String id,
            String method,
            String degradeLevel,
            String imageObjectKey,
            String imageContentType
    ) {
        FoodLogEntity e = new FoodLogEntity();
        e.setId(id);
        e.setMethod(method);
        e.setDegradeLevel(degradeLevel);
        e.setImageObjectKey(imageObjectKey);
        e.setImageContentType(imageContentType);
        return e;
    }

    private static ObjectNode extractEffective(ProviderClient.ProviderResult result) {
        Object v = readMemberOrMethod(result, "effective");
        if (v instanceof ObjectNode obj) {
            return obj;
        }
        throw new IllegalStateException("Cannot extract effective from ProviderResult");
    }

    private static Object readMemberOrMethod(Object target, String name) {
        if (target == null) return null;

        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception ignore) {
            // ignore and try field
        }

        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static java.util.List<String> asTextArray(JsonNode node) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode it : node) {
            if (it != null && !it.isNull()) {
                out.add(it.asText());
            }
        }
        return out;
    }
}
