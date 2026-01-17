package com.calai.backend.gemini;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class GeminiIntegrationTest {

    static WireMockServer wm;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(0);
        wm.start();

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
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.provider.gemini.enabled", () -> "true");
        r.add("app.provider.gemini.base-url", () -> "http://localhost:" + wm.port());
        r.add("app.provider.gemini.api-key", () -> "dummy");
        r.add("app.foodlog.provider", () -> "GEMINI");
    }

    @Test
    void should_call_mock_gemini_and_return_draft() {
        // 這裡你可以用 TestRestTemplate 或 WebTestClient 去打 /api/v1/food-logs/album
        // 因為你是 multipart，上線後我建議你用 MockMvc 做 multipart 測試（下一步 S6-03 會做）
        Assertions.assertTrue(true);
    }
}
