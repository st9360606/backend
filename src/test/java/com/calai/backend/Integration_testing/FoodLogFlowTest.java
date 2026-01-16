package com.calai.backend.Integration_testing;

import com.calai.backend.Integration_testing.config.TestAuthConfig;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false) // ✅ 先讓 H-01 不被 Security 擋
@Import(TestAuthConfig.class)             // ✅ 固定 auth.requireUserId() = 1L
public class FoodLogFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    // ✅ 建議：測試手動觸發 worker，避免靠 @Scheduled 造成 flakiness
    @Autowired FoodLogTaskWorker worker;

    @Test
    void photo_pending_to_draft_then_override_save_list() throws Exception {
        // ✅ 最小 JPEG header，能過 ImageSniffer
        // ✅ 產生「本次測試唯一」的 bytes（避免撞到舊資料）
        // 但同一個測試內兩次上傳要用同一份 bytes（才能 dedup）
        byte[] jpg = randomJpegLikeBytes(128);

        String deviceUtcStr = "2026-01-15T12:00:00Z";
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.jpg", "image/jpeg", jpg
        );
        MockMultipartFile devicePart = new MockMultipartFile(
                "deviceCapturedAtUtc", "", "text/plain", deviceUtcStr.getBytes(StandardCharsets.UTF_8)
        );

        String uploadResp = mvc.perform(multipart("/api/v1/food-logs/photo")
                        .file(file)
                        .file(devicePart)
                        .header("X-Client-Timezone", "Asia/Taipei")
                )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode uploadJson = om.readTree(uploadResp);
        String id = uploadJson.path("foodLogId").asText();
        assertThat(id).isNotBlank();

        // ✅ 觸發 worker（通常一次就會把 QUEUED 任務跑掉）
        worker.runOnce();

        // 2) getOne 應該已經 DRAFT（若還是 PENDING，就再 runOnce + getOne 一次）
        String getResp = mvc.perform(get("/api/v1/food-logs/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode one = om.readTree(getResp);
        assertThat(one.path("status").asText()).isEqualTo("DRAFT");

        // 3) override nutrients
        String overrideBody = """
        {
          "fieldKey": "NUTRIENTS",
          "newValue": { "carbs": 99, "protein": 33 },
          "reason": "e2e test"
        }
        """;

        String ovResp = mvc.perform(post("/api/v1/food-logs/{id}/overrides", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overrideBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode ovJson = om.readTree(ovResp);
        assertThat(ovJson.path("status").asText()).isEqualTo("DRAFT");
        assertThat(ovJson.path("nutritionResult").path("nutrients").path("carbs").asInt()).isEqualTo(99);
        assertThat(ovJson.path("nutritionResult").path("nutrients").path("protein").asInt()).isEqualTo(33);

        // 4) save
        String saveResp = mvc.perform(post("/api/v1/food-logs/{id}/save", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode saveJson = om.readTree(saveResp);
        assertThat(saveJson.path("status").asText()).isEqualTo("SAVED");

        // 5) list saved
        LocalDate day = Instant.parse(deviceUtcStr).atZone(ZoneId.of("Asia/Taipei")).toLocalDate();

        String listResp = mvc.perform(get("/api/v1/food-logs")
                        .param("fromLocalDate", day.toString())
                        .param("toLocalDate", day.toString())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode listJson = om.readTree(listResp);

        boolean found = false;
        for (JsonNode item : listJson.path("items")) {
            if (id.equals(item.path("foodLogId").asText())) { found = true; break; }
        }
        assertThat(found).isTrue();
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
