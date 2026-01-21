package com.calai.backend.gemini;

import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;

import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
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

import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class FoodLogClientActionContractTest extends MySqlContainerBaseTest {

    static WireMockServer wm;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired FoodLogTaskWorker worker;
    @Autowired FoodLogTaskRepository taskRepo;
    @Autowired FoodLogRepository logRepo;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(0);
        wm.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.provider.gemini.enabled", () -> "true");
        r.add("app.provider.gemini.base-url", () -> "http://localhost:" + wm.port());
        r.add("app.provider.gemini.api-key", () -> "dummy");
        r.add("app.foodlog.provider", () -> "GEMINI");

        // 避免測試依賴 dev endpoints
        r.add("app.features.dev-debug-endpoints", () -> "false");
        r.add("app.features.dev-image-endpoint", () -> "false");
    }

    @TestConfiguration
    static class TestAuthOverrideConfig {
        @Bean @Primary
        public com.calai.backend.auth.security.AuthContext authContext() {
            return new com.calai.backend.auth.security.AuthContext() {
                @Override public Long requireUserId() { return 1L; }
            };
        }
    }

    private MockMultipartFile dummyJpg(String salt) {
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[3 + saltBytes.length];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        System.arraycopy(saltBytes, 0, bytes, 3, saltBytes.length);
        return new MockMultipartFile("file", "t.jpg", "image/jpeg", bytes);
    }

    private void runWorkerUntilNotQueuedOrRunning(String foodLogId, int maxLoops) {
        for (int i = 0; i < maxLoops; i++) {
            worker.runOnce();
            var tOpt = taskRepo.findByFoodLogId(foodLogId);
            if (tOpt.isPresent()) {
                String st = tOpt.get().getTaskStatus().name();
                if (!"QUEUED".equals(st) && !"RUNNING".equals(st)) return;
            }
        }
        throw new AssertionError("Worker did not process task. foodLogId=" + foodLogId);
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void rateLimited_should_return_clientAction_retryLater_and_retryAfterSec() throws Exception {
        wm.resetAll();
        wm.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "2")
                        .withBody("{\"error\":{\"message\":\"rate limited\"}}")));

        String resp1 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg("rate429-" + System.nanoTime()))
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.task.taskId").exists())
                .andReturn().getResponse().getContentAsString();

        String id = om.readTree(resp1).get("foodLogId").asText();

        runWorkerUntilNotQueuedOrRunning(id, 10);

        // 你現在 429 策略：task CANCELLED、log FAILED
        var log = logRepo.findById(id).orElseThrow();
        assertThat(log.getStatus().name()).isEqualTo("FAILED");
        assertThat(log.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");

        String resp2 = mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.errorCode").value("PROVIDER_RATE_LIMITED"))
                .andExpect(jsonPath("$.error.clientAction").value("RETRY_LATER"))
                .andExpect(jsonPath("$.error.retryAfterSec").value(2))
                // task 可能不回（因為你 getOne 只回 QUEUED/RUNNING/FAILED；429 是 CANCELLED）
                .andReturn().getResponse().getContentAsString();

        JsonNode j2 = om.readTree(resp2);
        // task 不一定存在（建議就是不回）
        assertThat(j2.get("task")).isNull();
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void blocked_should_return_clientAction_retakePhoto() throws Exception {
        wm.resetAll();
        wm.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"safety blocked\"}}")));

        String resp1 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg("blocked-" + System.nanoTime()))
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.task.taskId").exists())
                .andReturn().getResponse().getContentAsString();

        String id = om.readTree(resp1).get("foodLogId").asText();

        runWorkerUntilNotQueuedOrRunning(id, 10);

        mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.errorCode").value("PROVIDER_BLOCKED"))
                .andExpect(jsonPath("$.error.clientAction").value("RETAKE_PHOTO"));
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void timeout_should_return_clientAction_checkNetwork() throws Exception {
        wm.resetAll();
        wm.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(408)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "5")
                        .withBody("{\"error\":{\"message\":\"timeout\"}}")));

        String resp1 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg("timeout-" + System.nanoTime()))
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.task.taskId").exists())
                .andReturn().getResponse().getContentAsString();

        String id = om.readTree(resp1).get("foodLogId").asText();

        runWorkerUntilNotQueuedOrRunning(id, 10);

        mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.errorCode").value("PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.error.clientAction").value("CHECK_NETWORK"));
    }
}
