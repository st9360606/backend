package com.calai.backend.foodlog.provider.gemini;

import com.calai.backend.foodlog.provider.routing.AiModelTierRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.provider.gemini.support.GeminiJsonParsingSupport;
import com.calai.backend.foodlog.provider.gemini.GeminiProviderClient;
import com.calai.backend.foodlog.provider.gemini.config.GeminiProperties;
import com.calai.backend.foodlog.provider.gemini.image.GeminiPhotoAlbumProcessor;
import com.calai.backend.foodlog.provider.prompt.GeminiPromptFactory;
import com.calai.backend.foodlog.provider.gemini.transport.GeminiRequestBuilder;
import com.calai.backend.foodlog.provider.gemini.transport.GeminiTransportSupport;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.provider.support.ProviderTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.util.List;

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
    void photo_should_call_only_one_gemini_vision_request_and_never_use_text_model() throws Exception {
        String pathRegex = "/v1beta/models/.*:generateContent";

        String visionResp = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "functionCall": {
                      "name": "emitNutrition",
                      "args": {
                        "foodName": "Paella",
                        "quantity": {
                          "value": 1,
                          "unit": "SERVING"
                        },
                        "nutrients": {
                          "kcal": 420,
                          "protein": 18,
                          "fat": 12,
                          "carbs": 55,
                          "fiber": 3,
                          "sugar": 2,
                          "sodium": 680
                        },
                        "confidence": 0.6,
                        "healthScore": 7,
                        "warnings": [],
                        "labelMeta": {
                          "servingsPerContainer": null,
                          "basis": "ESTIMATED_PORTION"
                        }
                      }
                    }
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 100,
            "candidatesTokenCount": 50,
            "totalTokenCount": 150
          }
        }
        """;

        wm.stubFor(post(urlPathMatching(pathRegex))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(visionResp)));

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
        when(props.isLabelUseFunctionCalling()).thenReturn(false);
        when(props.getPhotoAlbum()).thenReturn(new GeminiProperties.RequestTuning(1024, 0.0));
        when(props.getLabelJson()).thenReturn(new GeminiProperties.RequestTuning(2048, 0.0));

        ProviderTelemetry telemetry = mock(ProviderTelemetry.class);

        AiModelTierRouter router = mock(AiModelTierRouter.class);
        when(router.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION)))
                .thenReturn(new AiModelTierRouter.Resolved("GEMINI", "gemini-vision"));

        GeminiRequestBuilder requestBuilder = new GeminiRequestBuilder(om, props);
        GeminiTransportSupport transportSupport = new GeminiTransportSupport(http, props, requestBuilder);
        GeminiJsonParsingSupport jsonParsingSupport = new GeminiJsonParsingSupport(om);
        GeminiPromptFactory promptFactory = new GeminiPromptFactory();

        GeminiPhotoAlbumProcessor processor = new GeminiPhotoAlbumProcessor(
                transportSupport,
                jsonParsingSupport,
                promptFactory,
                om,
                telemetry,
                router
        );

        GeminiProviderClient client = new GeminiProviderClient(List.of(processor));

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

        ProviderTelemetry providerTelemetry = telemetry;

        var out = client.process(e, storage);

        assertNotNull(out);
        assertEquals("GEMINI", out.provider());
        assertNotNull(out.effective());
        assertEquals("Paella", out.effective().get("foodName").asText());

        // ✅ 只打一個 Gemini API call
        wm.verify(1, postRequestedFor(urlPathMatching(pathRegex)));

        // ✅ 只走 vision model，不走 text model
        verify(router, times(1)).resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION));
        verify(router, never()).resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.TEXT));
        verify(router, never()).resolveOrThrow(eq(ModelTier.MODEL_TIER_LOW), eq(ModelMode.TEXT));

        // ✅ telemetry 只記一次 vision 成功
        verify(providerTelemetry, times(1)).ok(
                eq("GEMINI"),
                eq("gemini-vision"),
                eq("foodlog-1"),
                anyLong(),
                eq(100),
                eq(50),
                eq(150)
        );
    }
}
