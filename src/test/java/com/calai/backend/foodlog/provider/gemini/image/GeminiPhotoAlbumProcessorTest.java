package com.calai.backend.foodlog.provider.gemini.image;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.provider.gemini.support.GeminiJsonParsingSupport;
import com.calai.backend.foodlog.provider.gemini.transport.GeminiTransportSupport;
import com.calai.backend.foodlog.provider.prompt.GeminiPromptFactory;
import com.calai.backend.foodlog.provider.routing.AiModelTierRouter;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.provider.support.ProviderTelemetry;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiPhotoAlbumProcessorTest {

    private final ObjectMapper om = new ObjectMapper();

    private GeminiTransportSupport transportSupport;
    private GeminiJsonParsingSupport jsonParsingSupport;
    private GeminiPromptFactory promptFactory;
    private ProviderTelemetry telemetry;
    private AiModelTierRouter modelRouter;
    private StorageService storage;

    private GeminiPhotoAlbumProcessor processor;

    @BeforeEach
    void setUp() {
        transportSupport = mock(GeminiTransportSupport.class);
        jsonParsingSupport = mock(GeminiJsonParsingSupport.class);
        promptFactory = mock(GeminiPromptFactory.class);
        telemetry = mock(ProviderTelemetry.class);
        modelRouter = mock(AiModelTierRouter.class);
        storage = mock(StorageService.class, RETURNS_DEEP_STUBS);

        processor = new GeminiPhotoAlbumProcessor(
                transportSupport,
                jsonParsingSupport,
                promptFactory,
                om,
                telemetry,
                modelRouter
        );
    }

    @Test
    @DisplayName("supports: PHOTO / ALBUM 應回 true，其他 method 應回 false")
    void supports_should_match_photo_and_album_only() {
        assertThat(processor.supports("PHOTO")).isTrue();
        assertThat(processor.supports("photo")).isTrue();
        assertThat(processor.supports("ALBUM")).isTrue();

        assertThat(processor.supports("LABEL")).isFalse();
        assertThat(processor.supports("BARCODE")).isFalse();
        assertThat(processor.supports(null)).isFalse();
    }

    @Test
    @DisplayName("process: functionArgs 直接提供有效 JSON 時應回成功結果並記錄 telemetry.ok")
    void process_should_return_success_when_function_args_are_valid() throws Exception {
        FoodLogEntity entity = newEntity("foodlog-1", "PHOTO", "DG-0", "obj/photo-1.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-vision-test");
        when(modelRouter.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION))).thenReturn(resolved);

        when(storage.open("obj/photo-1.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        when(promptFactory.mainPrompt(false)).thenReturn("PROMPT");

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(100);
        when(tok.candTok()).thenReturn(50);
        when(tok.totalTok()).thenReturn(150);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(om.readTree("""
                {
                  "foodName":"Protein Shake",
                  "quantity":{"value":1,"unit":"BOTTLE"},
                  "nutrients":{
                    "kcal":240,
                    "protein":20,
                    "fat":6,
                    "carbs":18,
                    "fiber":1,
                    "sugar":10,
                    "sodium":180
                  },
                  "confidence":0.82,
                  "labelMeta":{"servingsPerContainer":1,"basis":"ESTIMATED_PORTION"}
                }
                """));
        when(callResult.text()).thenReturn(null);
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(
                any(byte[].class),
                anyString(),
                eq("PROMPT"),
                eq("gemini-vision-test"),
                eq(false),
                eq("foodlog-1")
        )).thenReturn(callResult);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").asText()).isEqualTo("Protein Shake");
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("BOTTLE");
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(240.0d);
        assertThat(effective.get("confidence").asDouble()).isEqualTo(0.82d);

        verify(telemetry).ok(
                eq("GEMINI"),
                eq("gemini-vision-test"),
                eq("foodlog-1"),
                anyLong(),
                eq(100),
                eq(50),
                eq(150)
        );
        verify(telemetry, never()).fail(anyString(), anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("process: functionArgs 為 null 時應走 text -> tryParseJson 路徑")
    void process_should_parse_text_when_function_args_is_null() throws Exception {
        FoodLogEntity entity = newEntity("foodlog-2", "ALBUM", "DG-2", "obj/album-1.jpg", "image/png");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-vision-lite");
        when(modelRouter.resolveOrThrow(eq(ModelTier.MODEL_TIER_LOW), eq(ModelMode.VISION))).thenReturn(resolved);

        when(storage.open("obj/album-1.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{9, 8, 7}));

        when(promptFactory.mainPrompt(false)).thenReturn("PROMPT");

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(10);
        when(tok.candTok()).thenReturn(20);
        when(tok.totalTok()).thenReturn(30);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(null);
        when(callResult.text()).thenReturn("RAW_TEXT");
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(
                any(byte[].class),
                anyString(),
                eq("PROMPT"),
                eq("gemini-vision-lite"),
                eq(false),
                eq("foodlog-2")
        )).thenReturn(callResult);

        JsonNode parsed = om.readTree("""
                {
                  "foodName":"Chocolate Milk",
                  "quantity":{"value":1,"unit":"BOTTLE"},
                  "nutrients":{
                    "kcal":180,
                    "protein":8,
                    "fat":5,
                    "carbs":22,
                    "fiber":0,
                    "sugar":20,
                    "sodium":160
                  },
                  "confidence":0.75
                }
                """);
        when(jsonParsingSupport.tryParseJson("RAW_TEXT")).thenReturn(parsed);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").asText()).isEqualTo("Chocolate Milk");
        assertThat(effective.get("nutrients").get("protein").asDouble()).isEqualTo(8.0d);

        verify(jsonParsingSupport).tryParseJson("RAW_TEXT");
        verify(telemetry).ok(
                eq("GEMINI"),
                eq("gemini-vision-lite"),
                eq("foodlog-2"),
                anyLong(),
                eq(10),
                eq(20),
                eq(30)
        );
    }

    @Test
    @DisplayName("process: parsed 為 null 或非 object 時應 fallback 為 UNKNOWN_FOOD")
    void process_should_fallback_unknown_food_when_parsed_is_invalid() throws Exception {
        FoodLogEntity entity = newEntity("foodlog-3", "PHOTO", "DG-0", "obj/photo-invalid.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-vision-test");
        when(modelRouter.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION))).thenReturn(resolved);

        when(storage.open("obj/photo-invalid.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{1}));

        when(promptFactory.mainPrompt(false)).thenReturn("PROMPT");

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(1);
        when(tok.candTok()).thenReturn(2);
        when(tok.totalTok()).thenReturn(3);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(null);
        when(callResult.text()).thenReturn("NOT_JSON");
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(any(byte[].class), anyString(), anyString(), anyString(), eq(false), eq("foodlog-3")))
                .thenReturn(callResult);
        when(jsonParsingSupport.tryParseJson("NOT_JSON")).thenReturn(null);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").isNull()).isTrue();
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("SERVING");
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("warnings")).hasSize(2);
        assertThat(effective.get("warnings").get(0).asText()).isEqualTo("UNKNOWN_FOOD");
        assertThat(effective.get("warnings").get(1).asText()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("process: normalize 失敗時應 fallback 為 UNKNOWN_FOOD")
    void process_should_fallback_unknown_food_when_normalize_fails() throws Exception {
        FoodLogEntity entity = newEntity("foodlog-4", "PHOTO", "DG-0", "obj/photo-bad.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-vision-test");
        when(modelRouter.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION))).thenReturn(resolved);

        when(storage.open("obj/photo-bad.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));

        when(promptFactory.mainPrompt(false)).thenReturn("PROMPT");

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(11);
        when(tok.candTok()).thenReturn(22);
        when(tok.totalTok()).thenReturn(33);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(om.readTree("""
                {
                  "foodName":"Broken Result",
                  "quantity":{"value":1,"unit":"PACK"}
                }
                """)); // 缺 nutrients，normalize 會失敗
        when(callResult.text()).thenReturn(null);
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(any(byte[].class), anyString(), anyString(), anyString(), eq(false), eq("foodlog-4")))
                .thenReturn(callResult);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").isNull()).isTrue();
        assertThat(effective.get("warnings").get(0).asText()).isEqualTo("UNKNOWN_FOOD");
        assertThat(effective.get("warnings").get(1).asText()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("process: whole-container + 核心營養全 0 + low confidence 時應 fallback 為 MISSING_NUTRITION_FACTS")
    void process_should_fallback_missing_nutrition_facts_for_low_confidence_whole_container() throws Exception {
        FoodLogEntity entity = newEntity("foodlog-5", "PHOTO", "DG-0", "obj/photo-pack.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-vision-test");
        when(modelRouter.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION))).thenReturn(resolved);

        when(storage.open("obj/photo-pack.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{5, 5, 5}));

        when(promptFactory.mainPrompt(false)).thenReturn("PROMPT");

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(101);
        when(tok.candTok()).thenReturn(102);
        when(tok.totalTok()).thenReturn(203);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(om.readTree("""
                {
                  "foodName":"Potato Chips",
                  "quantity":{"value":1,"unit":"PACK"},
                  "nutrients":{
                    "kcal":0,
                    "protein":0,
                    "fat":0,
                    "carbs":0,
                    "fiber":0,
                    "sugar":0,
                    "sodium":0
                  },
                  "confidence":0.3
                }
                """));
        when(callResult.text()).thenReturn(null);
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(any(byte[].class), anyString(), anyString(), anyString(), eq(false), eq("foodlog-5")))
                .thenReturn(callResult);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        assertThat(effective.get("foodName").asText()).isEqualTo("Potato Chips");
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("PACK");
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("warnings")).hasSize(2);
        assertThat(effective.get("warnings").get(0).asText()).isEqualTo("MISSING_NUTRITION_FACTS");
        assertThat(effective.get("warnings").get(1).asText()).isEqualTo("LOW_CONFIDENCE");
        assertThat(effective.get("labelMeta").get("basis").asText()).isEqualTo("ESTIMATED_PORTION");
    }

    @Test
    @DisplayName("process: 原始 parsed 含 NO_FOOD_DETECTED warning 時應直接回 effective，不走 UNKNOWN_FOOD fallback")
    void process_should_return_effective_directly_when_no_food_detected_warning_exists() throws Exception {
        FoodLogEntity entity = newEntity("foodlog-6", "PHOTO", "DG-0", "obj/photo-no-food.jpg", "image/jpeg");

        AiModelTierRouter.Resolved resolved = mock(AiModelTierRouter.Resolved.class);
        when(resolved.modelId()).thenReturn("gemini-vision-test");
        when(modelRouter.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION))).thenReturn(resolved);

        when(storage.open("obj/photo-no-food.jpg").inputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{7, 7}));

        when(promptFactory.mainPrompt(false)).thenReturn("PROMPT");

        GeminiTransportSupport.Tok tok = mock(GeminiTransportSupport.Tok.class);
        when(tok.promptTok()).thenReturn(9);
        when(tok.candTok()).thenReturn(9);
        when(tok.totalTok()).thenReturn(18);

        GeminiTransportSupport.CallResult callResult = mock(GeminiTransportSupport.CallResult.class);
        when(callResult.functionArgs()).thenReturn(om.readTree("""
                {
                  "foodName": null,
                  "quantity":{"value":1,"unit":"SERVING"},
                  "nutrients":{
                    "kcal":0,
                    "protein":0,
                    "fat":0,
                    "carbs":0,
                    "fiber":0,
                    "sugar":0,
                    "sodium":0
                  },
                  "confidence":0.1,
                  "warnings":["NO_FOOD_DETECTED"]
                }
                """));
        when(callResult.text()).thenReturn(null);
        when(callResult.tok()).thenReturn(tok);

        when(transportSupport.callAndExtract(any(byte[].class), anyString(), anyString(), anyString(), eq(false), eq("foodlog-6")))
                .thenReturn(callResult);

        ProviderClient.ProviderResult result = processor.process(entity, storage);

        ObjectNode effective = extractEffective(result);

        // 應保留 normalize 後的 warning，而不是轉成 UNKNOWN_FOOD fallback
        assertThat(effective.get("warnings")).hasSize(1);
        assertThat(effective.get("warnings").get(0).asText()).isEqualTo("NO_FOOD_DETECTED");
        assertThat(effective.get("foodName").isNull()).isTrue();
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

    @SuppressWarnings("SameParameterValue")
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
}
