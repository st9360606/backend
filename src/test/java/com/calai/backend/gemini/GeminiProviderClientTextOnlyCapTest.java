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
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class GeminiProviderClientTextOnlyCapTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void photo_should_call_at_most_one_text_only_repair_no_name_only_estimate() throws Exception {
        String pathRegex = "/v1beta/models/.*:generateContent";

        String visionResp = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\\"foodName\\":\\"Paella\\",\\"quantity\\":{\\"value\\":1,\\"unit\\":\\"SERVING\\"},\\"nutrients\\":{\\"kcal\\":420,\\"protein\\":null,\\"fat\\":null,\\"carbs\\":null,\\"fiber\\":null,\\"sugar\\":null,\\"sodium\\":null},\\"confidence\\":0.6,\\"warnings\\":[] }"}]}}],
          "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":50,"totalTokenCount":150}
        }
        """;

        String repairResp = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\\"foodName\\":\\"Paella\\",\\"quantity\\":{\\"value\\":1,\\"unit\\":\\"SERVING\\"},\\"nutrients\\":{\\"kcal\\":null,\\"protein\\":null,\\"fat\\":null,\\"carbs\\":null,\\"fiber\\":null,\\"sugar\\":null,\\"sodium\\":null},\\"confidence\\":0.2,\\"warnings\\":[\\"LOW_CONFIDENCE\\"] }"}]}}],
          "usageMetadata":{"promptTokenCount":80,"candidatesTokenCount":40,"totalTokenCount":120}
        }
        """;

        wm.stubFor(post(urlPathMatching(pathRegex))
                .inScenario("seq")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(visionResp))
                .willSetStateTo("REPAIR"));

        wm.stubFor(post(urlPathMatching(pathRegex))
                .inScenario("seq")
                .whenScenarioStateIs("REPAIR")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(repairResp)));

        ObjectMapper om = new ObjectMapper();
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        RestClient http = RestClient.builder()
                .baseUrl(wm.getRuntimeInfo().getHttpBaseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(jdk))
                .build();

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
        assertNotNull(out.effective());

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
}