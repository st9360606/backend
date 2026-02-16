package com.calai.backend.gemini;

import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.provider.GeminiProviderClient;
import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiProviderClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static RestClient restClientHttp11(String baseUrl) {
        // ✅ 避免 RestClient / JDK HttpClient 用到 HTTP/2(h2c) 導致 WireMock EOF
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(jdk))
                .build();
    }

    /**
     * ✅ 用 ObjectMapper 組「合法 Gemini 回應 JSON」
     * - 你不用再手寫 candidates 的括號
     * - "text" 裡面有換行也會自動 escape 成 \\n，不會再炸 CTRL-CHAR
     */
    private static String geminiRespWithText(ObjectMapper om, String text, int p, int c, int t) throws Exception {
        ObjectNode root = om.createObjectNode();
        var candidates = root.putArray("candidates");
        var cand0 = candidates.addObject();
        var content = cand0.putObject("content");
        var parts = content.putArray("parts");
        parts.addObject().put("text", text);

        var usage = root.putObject("usageMetadata");
        usage.put("promptTokenCount", p);
        usage.put("candidatesTokenCount", c);
        usage.put("totalTokenCount", t);

        return om.writeValueAsString(root);
    }

    @Test
    void photo_should_call_main_and_at_most_one_text_repair_then_fallback_unknown() throws Exception {
        String pathRegex = "/v1beta/models/.*:generateContent";
        ObjectMapper om = new ObjectMapper();

        // 1) main (VISION): foodName 有，但 macros 全 null => gate fail => trigger repair
        String mainText = """
        {"foodName":"Paella","quantity":{"value":1,"unit":"SERVING"},
         "nutrients":{"kcal":420,"protein":null,"fat":null,"carbs":null,"fiber":null,"sugar":null,"sodium":null},
         "confidence":0.6,"warnings":[]}
        """.replace("\n", "").replace("\r", "").replace(" ", "");

        // 2) TEXT-ONLY repair：仍失敗（全部 null） -> 最後會走 fallbackUnknownFoodFromBrokenText()
        String repairText = """
        {"foodName":"Paella","quantity":{"value":1,"unit":"SERVING"},
         "nutrients":{"kcal":null,"protein":null,"fat":null,"carbs":null,"fiber":null,"sugar":null,"sodium":null},
         "confidence":0.2,"warnings":["LOW_CONFIDENCE"]}
        """.replace("\n", "").replace("\r", "").replace(" ", "");

        String mainResp   = geminiRespWithText(om, mainText,   100, 50, 150);
        String repairResp = geminiRespWithText(om, repairText,  80, 40, 120);

        wm.stubFor(post(urlPathMatching(pathRegex))
                .inScenario("seq-photo")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(mainResp))
                .willSetStateTo("REPAIR"));

        wm.stubFor(post(urlPathMatching(pathRegex))
                .inScenario("seq-photo")
                .whenScenarioStateIs("REPAIR")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(repairResp)));

        RestClient http = restClientHttp11(wm.getRuntimeInfo().getHttpBaseUrl());

        GeminiProperties props = mock(GeminiProperties.class);
        when(props.getApiKey()).thenReturn("TEST_API_KEY");
        when(props.getMaxOutputTokens()).thenReturn(1024);
        when(props.getTemperature()).thenReturn(0.0);
        when(props.isLabelUseFunctionCalling()).thenReturn(false);

        ProviderTelemetry telemetry = mock(ProviderTelemetry.class);

        AiModelRouter router = mock(AiModelRouter.class);
        when(router.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION)))
                .thenReturn(new AiModelRouter.Resolved("GEMINI", "gemini-vision"));
        when(router.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.TEXT)))
                .thenReturn(new AiModelRouter.Resolved("GEMINI", "gemini-text"));

        GeminiProviderClient client = new GeminiProviderClient(http, props, om, telemetry, router);

        StorageService storage = mock(StorageService.class);
        when(storage.open(anyString())).thenReturn(
                new StorageService.OpenResult(
                        new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        3L,
                        "image/jpeg"
                )
        );

        FoodLogEntity e = new FoodLogEntity();
        e.setId("foodlog-1");
        e.setMethod("PHOTO");
        e.setDegradeLevel("DG-0");
        e.setImageObjectKey("k1");
        e.setImageContentType("image/jpeg");

        var out = client.process(e, storage);

        assertNotNull(out);
        assertEquals("GEMINI", out.provider());

        JsonNode effective = out.effective();
        assertNotNull(effective);
        assertEquals("Paella", effective.path("foodName").asText());

        // ✅ repair 失敗後會走 fallbackUnknownFoodFromBrokenText -> UNKNOWN_FOOD + LOW_CONFIDENCE
        assertTrue(containsWarning(effective, FoodLogWarning.UNKNOWN_FOOD.name()));
        assertTrue(containsWarning(effective, FoodLogWarning.LOW_CONFIDENCE.name()));

        // ✅ 最關鍵：只會 hit 2 次（main + text-only repair）
        wm.verify(2, postRequestedFor(urlPathMatching(pathRegex)));

        // ✅ token 加總：100+80=180, 50+40=90, 150+120=270
        verify(telemetry, atLeastOnce()).ok(
                eq("GEMINI"),
                eq("gemini-vision"),
                eq("foodlog-1"),
                anyLong(),
                eq(180),
                eq(90),
                eq(270)
        );
    }

    @Test
    void label_should_scale_to_whole_package_and_clear_servings_meta() throws Exception {
        String pathRegex = "/v1beta/models/.*:generateContent";
        ObjectMapper om = new ObjectMapper();

        // ✅ 注意：label 的 "text" 內容就算有換行也沒差，helper 會幫你 escape
        String labelText = """
        {
          "foodName":"Test Product",
          "quantity":{"value":30,"unit":"GRAM"},
          "nutrients":{"kcal":100,"protein":null,"fat":null,"carbs":null,"fiber":null,"sugar":null,"sodium":10},
          "confidence":0.9,
          "warnings":[],
          "labelMeta":{"servingsPerContainer":2,"basis":"PER_SERVING"}
        }
        """;

        String labelResp = geminiRespWithText(om, labelText, 10, 2, 12);

        wm.stubFor(post(urlPathMatching(pathRegex))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(labelResp)));

        RestClient http = restClientHttp11(wm.getRuntimeInfo().getHttpBaseUrl());

        GeminiProperties props = mock(GeminiProperties.class);
        when(props.getApiKey()).thenReturn("TEST_API_KEY");
        when(props.getMaxOutputTokens()).thenReturn(1024);
        when(props.getTemperature()).thenReturn(0.0);
        when(props.isLabelUseFunctionCalling()).thenReturn(false);

        ProviderTelemetry telemetry = mock(ProviderTelemetry.class);

        AiModelRouter router = mock(AiModelRouter.class);
        when(router.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION)))
                .thenReturn(new AiModelRouter.Resolved("GEMINI", "gemini-vision"));
        when(router.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.TEXT)))
                .thenReturn(new AiModelRouter.Resolved("GEMINI", "gemini-text"));

        GeminiProviderClient client = new GeminiProviderClient(http, props, om, telemetry, router);

        StorageService storage = mock(StorageService.class);
        when(storage.open(anyString())).thenReturn(
                new StorageService.OpenResult(
                        new ByteArrayInputStream(new byte[]{9, 9, 9}),
                        3L,
                        "image/jpeg"
                )
        );

        FoodLogEntity e = new FoodLogEntity();
        e.setId("foodlog-label-1");
        e.setMethod("LABEL");
        e.setDegradeLevel("DG-0");
        e.setImageObjectKey("k-label");
        e.setImageContentType("image/jpeg");

        var out = client.process(e, storage);

        JsonNode eff = out.effective();
        assertEquals("Test Product", eff.path("foodName").asText());

        // ✅ scale：100*2=200, 10*2=20
        assertEquals(200.0, eff.path("nutrients").path("kcal").asDouble(), 0.0001);
        assertEquals(20.0, eff.path("nutrients").path("sodium").asDouble(), 0.0001);

        // ✅ quantity -> 1 SERVING（代表整包）
        assertEquals(1.0, eff.path("quantity").path("value").asDouble(), 0.0001);
        assertEquals("SERVING", eff.path("quantity").path("unit").asText());

        // ✅ labelMeta：servings 清掉 + basis 改 WHOLE_PACKAGE
        assertTrue(eff.has("labelMeta"));
        assertTrue(eff.path("labelMeta").path("servingsPerContainer").isNull());
        assertEquals("WHOLE_PACKAGE", eff.path("labelMeta").path("basis").asText());

        wm.verify(1, postRequestedFor(urlPathMatching(pathRegex)));

        verify(telemetry, atLeastOnce()).ok(
                eq("GEMINI"),
                eq("gemini-vision"),
                eq("foodlog-label-1"),
                anyLong(),
                eq(10),
                eq(2),
                eq(12)
        );
    }

    private static boolean containsWarning(JsonNode effective, String code) {
        if (effective == null) return false;
        JsonNode w = effective.path("warnings");
        if (!w.isArray()) return false;
        for (JsonNode it : w) {
            if (it != null && !it.isNull() && code.equalsIgnoreCase(it.asText())) return true;
        }
        return false;
    }
}
