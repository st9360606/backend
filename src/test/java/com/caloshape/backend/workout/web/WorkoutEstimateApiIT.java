package com.caloshape.backend.workout.web;

import com.caloshape.backend.BackendApplication;
import com.caloshape.backend.auth.security.AuthContext;
import com.caloshape.backend.testsupport.db.MySqlContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc(addFilters = false) // 關掉安全 filter，避免 JWT 依賴
@TestPropertySource(properties = {
        "app.email.enabled=false",
        "spring.mail.test-connection=false",
        "logging.level.com.caloshape.backend=DEBUG",
        "logging.level.org.springframework.web=DEBUG",
        "server.error.include-message=always",
        "server.error.include-exception=true"
})
class WorkoutEstimateApiIT extends MySqlContainerBaseTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean
    AuthContext authContext; // 由這裡提供 userId

    @BeforeEach
    void seedUserProfile() {
        jdbc.update("""
            INSERT INTO users (id, email, status, created_at, updated_at)
            VALUES (1, 'workout-estimate@example.local', 'ACTIVE', NOW(), NOW())
            ON DUPLICATE KEY UPDATE updated_at = NOW()
        """);
        jdbc.update("""
            INSERT INTO user_profiles
              (user_id, weight_kg, locale, timezone,
               daily_step_goal, unit_preference, daily_workout_goal_kcal,
               kcal, carbs_g, protein_g, fat_g, fiber_g, sugar_g, sodium_mg,
               water_ml, water_mode, bmi, bmi_class, plan_mode, calc_version,
               created_at, updated_at)
            VALUES (1, 64.80, 'zh-TW', 'Asia/Taipei',
                    10000, 'KG', 450,
                    0, 0, 0, 0, 35, 0, 2300,
                    0, 'AUTO', 0, 'UNKNOWN', 'AUTO', 'healthcalc_v1',
                    NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              weight_kg = 64.80,
              locale = 'zh-TW',
              timezone = 'Asia/Taipei',
              updated_at = NOW()
        """);
        jdbc.update("""
            INSERT INTO user_entitlements
              (id, user_id, entitlement_type, status, valid_from_utc, valid_to_utc,
               source, product_id, subscription_state, payment_state,
               created_at_utc, updated_at_utc)
            VALUES ('00000000-0000-0000-0000-000000000101', 1, 'YEARLY', 'ACTIVE',
                    DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY),
                    DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY),
                    'INTERNAL', 'workout-estimate-test', 'TEST_ACTIVE', 'OK',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
              valid_to_utc = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY),
              status = 'ACTIVE',
              payment_state = 'OK',
              updated_at_utc = UTC_TIMESTAMP(6)
        """);
    }

    @Test
    void estimate_zhMinutes_running_ok() throws Exception {
        Long userId = 1L;

        Integer ok = jdbc.queryForObject(
                "select count(*) from user_profiles where user_id=? and (weight_kg is not null or weight_lbs is not null)",
                Integer.class, userId
        );
        if (ok == null || ok == 0) {
            throw new IllegalStateException("user_id=1 無體重，請先補 user_profiles.weight_kg 或 weight_lbs");
        }

        when(authContext.requireUserId()).thenReturn(userId);

        String json = "{\"text\":\"45分 running\"}";

        mvc.perform(post("/api/v1/workouts/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .header("X-Client-Timezone", "Asia/Taipei")
                        .content(json))
                .andDo(print()) // 失敗時印堆疊
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.minutes").value(45))
                .andExpect(jsonPath("$.kcal").isNumber());
    }
}
