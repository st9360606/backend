package com.calai.backend.workout;

import com.calai.backend.BackendApplication;
import com.calai.backend.auth.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc(addFilters = false) // 關掉安全 filter，避免 JWT 依賴
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.email.enabled=false",
        "spring.mail.test-connection=false",
        "logging.level.com.calai.backend=DEBUG",
        "logging.level.org.springframework.web=DEBUG",
        "server.error.include-message=always",
        "server.error.include-exception=true"
})
class WorkoutEstimateIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean
    AuthContext authContext; // 由這裡提供 userId

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
