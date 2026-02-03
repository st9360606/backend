package com.calai.backend.Integration_testing;

import com.calai.backend.Integration_testing.config.TestAuthConfig;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(TestAuthConfig.class)
public class FoodLogDedupQuotaTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired FoodLogTaskWorker worker;

    @Autowired
    UserAiQuotaStateRepository quotaStateRepo;
    @Autowired FoodLogTaskRepository taskRepo;

    @Test
    void same_image_should_hit_dedup_no_quota_no_task() throws Exception {
        Long userId = 1L;
        ZoneId tz = ZoneId.of("Asia/Taipei");

        // ✅ 讓測試穩定：清掉 quota state（避免前一個測試留下計數）
        quotaStateRepo.deleteById(userId);

        // ✅ 產生「本次測試唯一」的 bytes（避免撞到舊資料）
        // 但同一個測試內兩次上傳要用同一份 bytes（才能 dedup）
        byte[] jpg = randomJpegLikeBytes(128);

        int beforeUsed = usedTotal(userId);

        // ========== 第一次：PHOTO（預期：PENDING + 扣 quota + 建 task）==========
        String deviceUtcStr = Instant.now().minusSeconds(60).toString(); // ✅ 30 天內，避免 suspect fallback
        MockMultipartFile file1 = new MockMultipartFile("file", "demo.jpg", "image/jpeg", jpg);
        MockMultipartFile devicePart = new MockMultipartFile(
                "deviceCapturedAtUtc", "", "text/plain",
                deviceUtcStr.getBytes(StandardCharsets.UTF_8)
        );

        String r1 = mvc.perform(multipart("/api/v1/food-logs/photo")
                        .file(file1)
                        .file(devicePart)
                        .header("X-Client-Timezone", tz.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode j1 = om.readTree(r1);
        String id1 = j1.path("foodLogId").asText();
        assertThat(id1).isNotBlank();
        assertThat(j1.path("status").asText()).isEqualTo("PENDING");
        assertThat(j1.path("task").isMissingNode()).isFalse();

        int usedAfterFirst = usedTotal(userId);
        assertThat(usedAfterFirst).isEqualTo(beforeUsed + 1); // ✅ 第一次應扣 1（現在看的是 user_ai_quota_state）

        // 讓 worker 把它跑完 → DRAFT
        worker.runOnce();

        String g1 = mvc.perform(get("/api/v1/food-logs/{id}", id1))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode g1j = om.readTree(g1);
        assertThat(g1j.path("status").asText()).isEqualTo("DRAFT");
        assertThat(g1j.path("nutritionResult").isNull()).isFalse();

        // ========== 第二次：ALBUM（同 bytes，預期：命中去重 → DRAFT + 不扣 quota + 不建 task）==========
        MockMultipartFile file2 = new MockMultipartFile("file", "demo.jpg", "image/jpeg", jpg);

        String r2 = mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(file2)
                        .header("X-Client-Timezone", tz.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode j2 = om.readTree(r2);
        String id2 = j2.path("foodLogId").asText();
        assertThat(id2).isNotBlank();
        assertThat(j2.path("status").asText()).isEqualTo("DRAFT");
        assertThat(j2.path("nutritionResult").isNull()).isFalse();
        assertThat(j2.path("task").isMissingNode() || j2.path("task").isNull()).isTrue();

        int usedAfterSecond = usedTotal(userId);
        assertThat(usedAfterSecond).isEqualTo(usedAfterFirst); // ✅ 第二次命中，不再扣

        // ✅ DB 層：第二筆不應該有 task row
        // （若你的 dedup 設計是「回舊的 foodLogId」，那這條會失敗；但你目前規格/測試期待是「dedup 回新 log 但無 task」）
        assertThat(taskRepo.findByFoodLogId(id2)).isEmpty();
    }

    /**
     * ✅ 兼容 TRIAL / PAID：AiQuotaEngine 可能增加 daily 或 monthly
     * 所以這裡用 dailyCount + monthlyCount 當總 used（適合這支測試的「增量」斷言）
     */
    private int usedTotal(Long userId) {
        return quotaStateRepo.findById(userId)
                .map(s -> safeInt(s.getDailyCount()) + safeInt(s.getMonthlyCount()))
                .orElse(0);
    }

    private static int safeInt(Integer v) {
        return v == null ? 0 : v;
    }

    private static byte[] randomJpegLikeBytes(int size) {
        byte[] b = new byte[Math.max(size, 16)];
        new SecureRandom().nextBytes(b);
        // JPEG magic bytes: FF D8 FF
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }
}