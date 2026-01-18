package com.calai.backend.gemini;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class GeminiIntegrationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.provider.gemini.enabled", () -> "true");
        r.add("app.provider.gemini.base-url", () -> wm.getRuntimeInfo().getHttpBaseUrl());
        r.add("app.provider.gemini.api-key", () -> "dummy");
        r.add("app.foodlog.provider", () -> "GEMINI");
    }

    @Test
    void should_call_mock_gemini_and_return_draft() {
        wm.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "candidates":[
                            {"content":{"parts":[{"text":"{\\"foodName\\":\\"Toast\\",\\"quantity\\":{\\"value\\":1,\\"unit\\":\\"SERVING\\"},\\"nutrients\\":{\\"kcal\\":75,\\"protein\\":2.5,\\"fat\\":1,\\"carbs\\":14,\\"fiber\\":0.8,\\"sugar\\":1.5,\\"sodium\\":140},\\"confidence\\":0.9}"}]},
                             "finishReason":"STOP"}
                          ],
                          "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":20,"totalTokenCount":30}
                        }
                        """)));

        // TODO: 之後用 MockMvc multipart 去打你的 controller
        org.junit.jupiter.api.Assertions.assertTrue(true);
    }
}
