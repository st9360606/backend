package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FoodLogGetOneWarningsContractTest extends MySqlContainerBaseTest {

    static final AtomicLong UID = new AtomicLong(0);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Autowired UserRepo userRepo;
    @Autowired FoodLogRepository foodLogRepo;

    @TestConfiguration
    static class TestAuthOverrideConfig {
        @Bean
        @Primary
        public com.calai.backend.auth.security.AuthContext authContext() {
            return new com.calai.backend.auth.security.AuthContext() {
                @Override public Long requireUserId() { return UID.get(); }
            };
        }
    }

    @Test
    @Order(1)
    @WithMockUser(username = "test", roles = {"USER"})
    void getOne_should_include_warnings_and_degradedReason_in_nutritionResult() throws Exception {
        // 1) user
        User u = new User();
        u.setEmail("warn@example.com");
        u.setProvider(com.calai.backend.auth.entity.AuthProvider.EMAIL);
        u.setStatus("ACTIVE");
        userRepo.saveAndFlush(u);
        UID.set(u.getId());

        // 2) food log (DRAFT) + effective contains UNKNOWN_FOOD + aiMeta.degradedReason
        Instant now = Instant.now();

        FoodLogEntity f = new FoodLogEntity();
        f.setUserId(u.getId());
        f.setStatus(FoodLogStatus.DRAFT);
        f.setMethod("ALBUM");
        f.setProvider("GEMINI");
        f.setDegradeLevel("DG-0");

        f.setCapturedAtUtc(now);
        f.setCapturedTz("Asia/Taipei");
        f.setCapturedLocalDate(LocalDate.ofInstant(now, ZoneId.of("Asia/Taipei")));
        f.setServerReceivedAtUtc(now);
        f.setTimeSource(TimeSource.SERVER_RECEIVED);
        f.setTimeSuspect(false);

        // effective JSON
        var eff = om.readTree("""
            {
              "quantity": {"value": 1.0, "unit": "SERVING"},
              "nutrients": {"kcal": null, "protein": null, "fat": null, "carbs": null, "fiber": null, "sugar": null, "sodium": null},
              "confidence": 0.2,
              "warnings": ["UNKNOWN_FOOD", "LOW_CONFIDENCE"],
              "aiMeta": {"degradedReason": "UNKNOWN_FOOD", "degradedAtUtc": "2026-01-01T00:00:00Z"}
            }
        """);

        f.setEffective(eff);
        foodLogRepo.saveAndFlush(f);

        // 3) call GET /api/v1/food-logs/{id}
        mvc.perform(get("/api/v1/food-logs/{id}", f.getId())
                        .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.nutritionResult").exists())
                // ✅ 你新增的欄位：warnings / degradedReason
                .andExpect(jsonPath("$.nutritionResult.warnings").isArray())
                .andExpect(jsonPath("$.nutritionResult.warnings", hasItem("UNKNOWN_FOOD")))
                .andExpect(jsonPath("$.nutritionResult.warnings", not(hasItem("NON_FOOD_SUSPECT"))))
                .andExpect(jsonPath("$.nutritionResult.degradedReason").value("UNKNOWN_FOOD"));
    }
}
