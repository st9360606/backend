package com.calai.backend.gemini;

import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.ModelMode;
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
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.io.ByteArrayInputStream;

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
        // ---- WireMock: same endpoint, sequential responses ----
        String pathRegex = "/v1beta/models/.*:generateContent";

        // 1) Vision: foodName 有，但 macros 全 null -> quality gate fail -> triggers repair
        String visionResp = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\\"foodName\\":\\"Paella\\",\\"quantity\\":{\\"value\\":1,\\"unit\\":\\"SERVING\\"},\\"nutrients\\":{\\"kcal\\":420,\\"protein\\":null,\\"fat\\":null,\\"carbs\\":null,\\"fiber\\":null,\\"sugar\\":null,\\"sodium\\":null},\\"confidence\\":0.6,\\"warnings\\":[] }"}]}}],
          "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":50,"totalTokenCount":150}
        }
        """;

        // 2) Text-only repair: 仍失敗（全 null） -> 最終 fallback；不應該再打第 3 次
        String repairResp = """
        {
          "candidates":[{"content":{"parts":[{"text":"{\\"foodName\\":\\"Paella\\",\\"quantity\\":{\\"value\\":1,\\"unit\\":\\"SERVING\\"},\\"nutrients\\":{\\"kcal\\":null,\\"protein\\":null,\\"fat\\":null,\\"carbs\\":null,\\"fiber\\":null,\\"sugar\\":null,\\"sodium\\":null},\\"confidence\\":0.2,\\"warnings\\":[\\"LOW_CONFIDENCE\\"] }"}]}}],
          "usageMetadata":{"promptTokenCount":80,"candidatesTokenCount":40,"totalTokenCount":120}
        }
        """;

        wm.stubFor(post(urlPathMatching(pathRegex))
                .inScenario("seq")
                .whenScenarioStateIs(Scenario.STARTED) // ✅ 修正：不要用 STARTED 變數
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(visionResp))
                .willSetStateTo("REPAIR"));

        wm.stubFor(post(urlPathMatching(pathRegex))
                .inScenario("seq")
                .whenScenarioStateIs("REPAIR")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(repairResp)));

        // ---- Dependencies ----
        ObjectMapper om = new ObjectMapper();
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // ✅ 強制 HTTP/1.1
                .build();

        RestClient http = RestClient.builder()
                .baseUrl(wm.getRuntimeInfo().getHttpBaseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(jdk))
                .build();

        // ✅ GeminiProperties：constructor 對不上 -> mock
        GeminiProperties props = mock(GeminiProperties.class);
        when(props.getApiKey()).thenReturn("TEST_API_KEY");
        when(props.getMaxOutputTokens()).thenReturn(1024);
        when(props.getTemperature()).thenReturn(0.0);
        when(props.isLabelUseFunctionCalling()).thenReturn(false);

        // ✅ ProviderTelemetry：constructor 對不上 -> mock
        ProviderTelemetry telemetry = mock(ProviderTelemetry.class);

        // ✅ Router：mock，回傳固定 modelId
        AiModelRouter router = mock(AiModelRouter.class);
        when(router.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.VISION)))
                .thenReturn(new AiModelRouter.Resolved("GEMINI", "gemini-vision"));
        when(router.resolveOrThrow(eq(ModelTier.MODEL_TIER_HIGH), eq(ModelMode.TEXT)))
                .thenReturn(new AiModelRouter.Resolved("GEMINI", "gemini-text"));

        GeminiProviderClient client = new GeminiProviderClient(http, props, om, telemetry, router);

        // ✅ StorageService：不是 functional interface -> mock
        StorageService storage = mock(StorageService.class);

        // 這段如果你的 OpenResult 建構子不同：就改成你實際的 new OpenResult(...) 形式
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
        e.setDegradeLevel("DG-0"); // => HIGH
        e.setImageObjectKey("k1");
        e.setImageContentType("image/jpeg");

        var out = client.process(e, storage);

        assertNotNull(out);
        assertEquals("GEMINI", out.provider());
        assertNotNull(out.effective());

        // ✅ 最關鍵：只會 hit 2 次（vision + text-only repair）
        wm.verify(2, postRequestedFor(urlPathMatching(pathRegex)));

        // ✅（加分）verify token 是加總：100+80=180, 50+40=90, 150+120=270
        // 如果你的 telemetry.ok(...) 參數順序/型別不同，就改成 any() 版本
        verify(telemetry, atLeastOnce()).ok(
                eq("GEMINI"),
                eq("gemini-vision"),     // 你現在 fallback 走 vision modelId（因為 repair 沒成功 return）
                eq("foodlog-1"),
                anyLong(),
                eq(180),
                eq(90),
                eq(270)
        );
    }
}
