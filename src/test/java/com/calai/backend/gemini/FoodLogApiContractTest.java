package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Step 6-03: API Contract Tests
 * - multipart upload /album /photo
 * - run worker.runOnce() to simulate async processing
 * - verify GET returns DRAFT with nutrients
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class FoodLogApiContractTest extends MySqlContainerBaseTest {

    static WireMockServer wm;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired FoodLogTaskWorker worker;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(0);
        wm.start();

        // ✅ Gemini mock：回一段 JSON text（candidates[0].content.parts[0].text）
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
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // ✅ 指到 WireMock，避免打外網
        r.add("app.provider.gemini.enabled", () -> "true");
        r.add("app.provider.gemini.base-url", () -> "http://localhost:" + wm.port());
        r.add("app.provider.gemini.api-key", () -> "dummy");
        r.add("app.foodlog.provider", () -> "GEMINI");

        // ✅ 減少排程干擾：可視情況關掉 dev endpoints
        r.add("app.features.dev-debug-endpoints", () -> "false");
        r.add("app.features.dev-image-endpoint", () -> "false");
    }

    /**
     * 測試用 AuthContext：固定 userId=1
     * 你 production 的 AuthContext 會依 JWT 解析，但測試只要可通過即可。
     */
    @TestConfiguration
    static class TestAuthOverrideConfig {
        @Bean
        @Primary
        public com.calai.backend.auth.security.AuthContext authContext() {
            return new com.calai.backend.auth.security.AuthContext() {
                @Override
                public Long requireUserId() { return 1L; }
            };
        }
    }

    private MockMultipartFile dummyJpg() {
        // 不需要真 jpg（你後端 ImageSniffer 會判 JPEG signature），所以要放 FF D8 FF
        byte[] jpgHeader = new byte[] {(byte)0xFF, (byte)0xD8, (byte)0xFF, 0x00, 0x01, 0x02, 0x03};
        return new MockMultipartFile("file", "t.jpg", "image/jpeg", jpgHeader);
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void album_should_pending_then_worker_to_draft() throws Exception {
        // 1) upload -> PENDING
        String resp1 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg())
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foodLogId").exists())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("PENDING"),
                        org.hamcrest.Matchers.is("DRAFT")
                )))
                .andReturn().getResponse().getContentAsString();

        JsonNode j1 = om.readTree(resp1);
        String id = j1.get("foodLogId").asText();

        // 如果去重命中可能直接 DRAFT；否則跑 worker 讓它完成
        if ("PENDING".equals(j1.get("status").asText())) {
            worker.runOnce();
        }

        // 2) GET -> DRAFT with nutrients
        String resp2 = mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.nutritionResult.foodName").value("Toast"))
                .andExpect(jsonPath("$.nutritionResult.nutrients.kcal").value(75.0))
                .andReturn().getResponse().getContentAsString();

        JsonNode j2 = om.readTree(resp2);
        assertThat(j2.path("nutritionResult").path("source").path("provider").asText()).isEqualTo("GEMINI");
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void album_dedup_should_return_draft_directly_on_second_upload() throws Exception {
        // 第一次
        String r1 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg())
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id1 = om.readTree(r1).get("foodLogId").asText();

        // 確保完成
        worker.runOnce();

        // 第二次（同一張圖）→ 期待去重命中：通常會直接 DRAFT
        String r2 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg())
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        JsonNode j2 = om.readTree(r2);
        assertThat(j2.get("foodLogId").asText()).isNotBlank();
        // 不一定等於 id1（你會建立新 log），但應該是 DRAFT
        assertThat(j2.get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void photo_should_work_too() throws Exception {
        MockMultipartFile file = dummyJpg();
        MockMultipartFile deviceCapturedAtUtc = new MockMultipartFile(
                "deviceCapturedAtUtc", "", "text/plain", "2026-01-18T00:00:00Z".getBytes()
        );

        String resp = mvc.perform(multipart("/api/v1/food-logs/photo")
                        .file(file)
                        .file(deviceCapturedAtUtc)
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foodLogId").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode j = om.readTree(resp);
        String id = j.get("foodLogId").asText();

        if ("PENDING".equals(j.get("status").asText())) worker.runOnce();

        mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.nutritionResult.nutrients.kcal").value(75.0));
    }
}
