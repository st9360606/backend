package com.calai.backend.gemini;

import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.packagedfood.ImageBarcodeDetector;
import com.calai.backend.foodlog.packagedfood.OpenFoodFactsSearchService;
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
import java.util.Optional;

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
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(jdk))
                .build();
    }

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

        String mainText = """
        {"foodName":"Paella","quantity":{"value":1,"unit":"SERVING"},
         "nutrients":{"kcal":420,"protein":null,"fat":null,"carbs":null,"fiber":null,"sugar":null,"sodium":null},
         "confidence":0.6,"warnings":[]}
        """.replace("\n", "").replace("\r", "").replace(" ", "");

        String repairText = """
        {"foodName":"Paella","quantity":{"value":1,"unit":"SERVING"},
         "nutrients":{"kcal":null,"protein":null,"fat":null,"carbs":null,"fiber":null,"sugar":null,"sodium":null},
         "confidence":0.2,"warnings":["LOW_CONFIDENCE"]}
        """.replace("\n", "").replace("\r", "").replace(" ", "");

        String mainResp = geminiRespWithText(om, mainText, 100, 50, 150);
        String repairResp = geminiRespWithText(om, repairText, 80, 40, 120);

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

        ImageBarcodeDetector imageBarcodeDetector = mock(ImageBarcodeDetector.class);
        when(imageBarcodeDetector.detect(any())).thenReturn(Optional.empty());

        BarcodeLookupService barcodeLookupService = mock(BarcodeLookupService.class);
        OpenFoodFactsSearchService offSearchService = mock(OpenFoodFactsSearchService.class);

        GeminiProviderClient client = new GeminiProviderClient(
                http,
                props,
                om,
                telemetry,
                router,
                imageBarcodeDetector,
                barcodeLookupService,
                offSearchService
        );

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

        // ✅ 走 fallback unknown 時，不再要求保留 tentative foodName
        assertTrue(effective.path("foodName").isMissingNode()
                   || effective.path("foodName").isNull()
                   || effective.path("foodName").asText().isBlank());

        assertTrue(containsWarning(effective, FoodLogWarning.UNKNOWN_FOOD.name()));
        assertTrue(containsWarning(effective, FoodLogWarning.LOW_CONFIDENCE.name()));

        wm.verify(2, postRequestedFor(urlPathMatching(pathRegex)));

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