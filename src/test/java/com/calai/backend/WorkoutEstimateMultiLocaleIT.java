package com.calai.backend;

import com.calai.backend.BackendApplication;
import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.workout.nlp.DurationParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.email.enabled=false",
        "spring.mail.test-connection=false",
        "server.error.include-message=always",
        "server.error.include-exception=true"
})
@TestInstance(Lifecycle.PER_CLASS)
class WorkoutEstimateMultiLocaleIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean
    AuthContext authContext;

    private static final Long TEST_UID = 1L;

    @BeforeAll
    void seedAndAssert() {
        String db = jdbc.queryForObject("select database()", String.class);
        System.out.println("[WorkoutEstimateMultiLocaleIT] database() = " + db);

        // ① users：簡單 upsert（不做任何子查詢；不動 email）
        jdbc.update("""
            INSERT INTO users (id, email, created_at, updated_at)
            VALUES (?, 'test+u1@example.local', NOW(), NOW())
            ON DUPLICATE KEY UPDATE 
              updated_at = NOW()
        """, TEST_UID);

        // ② user_profiles：不使用 alias 與 VALUES()；用參數重送
        //    參數順序： [VALUES 區] userId, kg, lbs, locale, tz
        //             +[UPDATE 區]  kg,   lbs,  locale, tz
        jdbc.update("""
            INSERT INTO user_profiles
              (user_id, weight_kg, weight_lbs, locale, timezone, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              weight_kg  = COALESCE(user_profiles.weight_kg,  ?),
              weight_lbs = COALESCE(user_profiles.weight_lbs, ?),
              locale     = COALESCE(user_profiles.locale,     ?),
              timezone   = COALESCE(user_profiles.timezone,   ?),
              updated_at = NOW()
        """, TEST_UID, 64.80, 143, "en", "Asia/Taipei",
                64.80, 143, "en", "Asia/Taipei");
    }

    @BeforeEach
    void stubAuthUser() {
        when(authContext.requireUserId()).thenReturn(TEST_UID);
    }

    // ---- 測資供應（可保留你現有版本） ----
    static List<String> locales() {
        return List.of("zh-CN","ja","ko","es","de","fr","tr","pl","nl","sv","da","nb","he",
                "pt-BR","pt-PT","vi","th","id","ms","fil","fi","ro","cs","hi","jv","it","en","zh-TW");
    }

    static final Map<String, String> MINUTE_WORD = Map.ofEntries(
            Map.entry("en","min"), Map.entry("zh-TW","分鐘"), Map.entry("zh-CN","分钟"),
            Map.entry("ja","分"), Map.entry("ko","분"), Map.entry("es","minutos"),
            Map.entry("de","minuten"), Map.entry("fr","minutes"), Map.entry("tr","dakika"),
            Map.entry("pl","minut"), Map.entry("nl","minuten"), Map.entry("sv","minuter"),
            Map.entry("da","minutter"), Map.entry("nb","minutter"), Map.entry("he","דקות"),
            Map.entry("pt-BR","minutos"), Map.entry("pt-PT","minutos"),
            Map.entry("vi","phut"), Map.entry("th","นาที"), Map.entry("id","menit"),
            Map.entry("ms","minit"), Map.entry("fil","minuto"), Map.entry("fi","minuuttia"),
            Map.entry("ro","minute"), Map.entry("cs","minut"), Map.entry("hi","मिनट"),
            Map.entry("jv","menit"), Map.entry("it","minuti")
    );

    static Stream<Object[]> scenarios() {
        return locales().stream().flatMap(loc -> {
            String m = MINUTE_WORD.getOrDefault(loc, "min");
            return Stream.of(
                    new Object[]{loc, "running 30 min", 30},
                    new Object[]{loc, "running 30 " + m, 30},
                    new Object[]{loc, "running 1 h 15 min", 75}
            );
        });
    }

    @ParameterizedTest(name = "{index}: {0} -> \"{1}\" = {2}min")
    @MethodSource("scenarios")
    void estimate_multiLocale_ok(String localeTag, String text, int expectMinutes) throws Exception {
        // 讓 service.userLocaleOrDefault() 取得目前語系
        jdbc.update("UPDATE user_profiles SET locale=? WHERE user_id=?", localeTag, TEST_UID);

        mvc.perform(post("/api/v1/workouts/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Client-Timezone", "Asia/Taipei")
                        .content("{\"text\":\"" + text.replace("\"","\\\"") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.minutes").value(expectMinutes))
                .andExpect(jsonPath("$.kcal").isNumber());
    }

    @Test void ko_minutes_should_parse() { // 韓文
        assertEquals(30, DurationParser.parseMinutes("running 30 분"));
    }

    @Test void th_minutes_should_parse() { // 泰文
        assertEquals(30, DurationParser.parseMinutes("running 30 นาที"));
    }

    @Test void hi_minutes_should_parse() { // 印地文
        assertEquals(30, DurationParser.parseMinutes("running 30 मिनट"));
    }
}
