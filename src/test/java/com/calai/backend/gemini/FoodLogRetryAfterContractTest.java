package com.calai.backend.gemini;

import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FoodLogRetryAfterContractTest extends MySqlContainerBaseTest {

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

        r.add("app.features.dev-debug-endpoints", () -> "false");
        r.add("app.features.dev-image-endpoint", () -> "false");
    }

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

    /**
     * ✅ 每次用不同 salt 產生不同 bytes，避免 SHA256 去重命中
     * JPEG signature：FF D8 FF 必須在最前面，ImageSniffer 才會認出來
     */
    private MockMultipartFile dummyJpg(String salt) {
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);

        byte[] bytes = new byte[3 + saltBytes.length];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        System.arraycopy(saltBytes, 0, bytes, 3, saltBytes.length);

        return new MockMultipartFile("file", "t.jpg", "image/jpeg", bytes);
    }

    /**
     * ✅ worker 可能會先撿到其他 QUEUED 任務，所以用 loop 等到「指定 foodLogId 的 task」離開 QUEUED/RUNNING
     */
    private void runWorkerUntilTaskLeavesQueued(String foodLogId, int maxLoops) {
        for (int i = 0; i < maxLoops; i++) {
            worker.runOnce();

            var tOpt = taskRepo.findByFoodLogId(foodLogId);
            if (tOpt.isPresent()) {
                String st = tOpt.get().getTaskStatus().name();
                if (!"QUEUED".equals(st) && !"RUNNING".equals(st)) return; // FAILED/CANCELLED/SUCCEEDED
            }
        }
        throw new AssertionError("Worker did not process target task within loops. foodLogId=" + foodLogId);
    }

    @Test
    @Order(1)
    @WithMockUser(username = "test", roles = {"USER"})
    void rateLimited_429_should_cancel_task_and_return_retryAfter_hint_in_api() throws Exception {
        // 1) Gemini mock：429 + Retry-After: 30
        wm.resetAll();
        wm.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "30")
                        .withBody("{\"error\":{\"message\":\"rate limited\"}}")));

        // 2) upload -> 必須是 PENDING + taskId 存在（避免 SHA256 去重命中）
        String resp1 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg("rate429-" + System.nanoTime()))
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.task.taskId").exists())
                .andReturn().getResponse().getContentAsString();

        String id = om.readTree(resp1).get("foodLogId").asText();

        // 3) run worker until our task processed -> now should CANCEL (no auto retry)
        runWorkerUntilTaskLeavesQueued(id, 10);

        var task = taskRepo.findByFoodLogId(id).orElseThrow();
        assertThat(task.getTaskStatus().name()).isEqualTo("CANCELLED");
        assertThat(task.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(task.getNextRetryAtUtc()).isNull(); // ✅ 你現在 429 不走 markFailed，所以不會有 nextRetryAtUtc

        // log 會是 FAILED + 帶 errorCode
        var log = logRepo.findById(id).orElseThrow();
        assertThat(log.getStatus().name()).isEqualTo("FAILED");
        assertThat(log.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(log.getLastErrorMessage()).contains("suggestedRetryAfterSec=30");

        // 4) GET should return FAILED and error.retryAfterSec=30; task 欄位通常不存在（因為 task 已 CANCELLED）
        mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.errorCode").value("PROVIDER_RATE_LIMITED"))
                .andExpect(jsonPath("$.error.retryAfterSec").value(30))
                .andExpect(jsonPath("$.task").doesNotExist());
    }


    @Test
    @Order(2)
    @WithMockUser(username = "test", roles = {"USER"})
    void blocked_should_cancel_task_and_get_should_return_422_modelRefused() throws Exception {
        // 1) Gemini mock：400 + body contains "safety" -> PROVIDER_REFUSED_SAFETY
        wm.resetAll();
        wm.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"safety blocked\"}}")));

        // 2) upload -> PENDING + taskId exists（避免 SHA256 去重命中）
        String resp1 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(dummyJpg("blocked-" + System.nanoTime()))
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.task.taskId").exists())
                .andReturn().getResponse().getContentAsString();

        String id = om.readTree(resp1).get("foodLogId").asText();

        // 3) run worker until processed
        runWorkerUntilTaskLeavesQueued(id, 10);

        // task：refused 屬於 non-retryable => CANCELLED（你原本就這樣做）
        var task = taskRepo.findByFoodLogId(id).orElseThrow();
        assertThat(task.getTaskStatus().name()).isEqualTo("CANCELLED");
        assertThat(task.getLastErrorCode()).isEqualTo("PROVIDER_REFUSED_SAFETY");
        assertThat(task.getNextRetryAtUtc()).isNull();

        // log：FAILED + refused code
        var log = logRepo.findById(id).orElseThrow();
        assertThat(log.getStatus().name()).isEqualTo("FAILED");
        assertThat(log.getLastErrorCode()).isEqualTo("PROVIDER_REFUSED_SAFETY");

        // 4) GET：v1.2 新契約 -> 422 MODEL_REFUSED（注意：回應 body 結構不是 $.status / $.error.*）
        mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MODEL_REFUSED"))
                .andExpect(jsonPath("$.refuseReason").value("SAFETY"))
                .andExpect(jsonPath("$.userMessageKey").value("PLEASE_PHOTO_FOOD_ONLY"))
                .andExpect(jsonPath("$.suggestedActions").isArray())
                .andExpect(jsonPath("$.requestId").exists());
    }
}
