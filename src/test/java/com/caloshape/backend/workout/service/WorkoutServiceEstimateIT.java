package com.caloshape.backend.workout.service;

import com.caloshape.backend.users.profile.entity.UserProfile;
import com.caloshape.backend.workout.dto.EstimateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional // Keep setup and test in one rollbacked persistence context.
class WorkoutServiceEstimateIT {
    private static final List<LocalizedWorkoutPhrase> APP_LOCALE_WORKOUT_PHRASES = List.of(
            new LocalizedWorkoutPhrase("en-US", "running", "minutes", false),
            new LocalizedWorkoutPhrase("ja", "ランニング", "分", true),
            new LocalizedWorkoutPhrase("de", "laufen", "minuten", false),
            new LocalizedWorkoutPhrase("fr", "course à pied", "minutes", false),
            new LocalizedWorkoutPhrase("it", "corsa", "minuti", false),
            new LocalizedWorkoutPhrase("nl", "hardlopen", "minuten", false),
            new LocalizedWorkoutPhrase("sv", "löpning", "minuter", false),
            new LocalizedWorkoutPhrase("fi", "juoksu", "minuuttia", false),
            new LocalizedWorkoutPhrase("pl", "bieganie", "minut", false),
            new LocalizedWorkoutPhrase("ko", "달리기", "분", true),
            new LocalizedWorkoutPhrase("es", "correr", "minutos", false),
            new LocalizedWorkoutPhrase("es-MX", "correr", "minutos", false),
            new LocalizedWorkoutPhrase("zh-CN", "跑步", "分钟", true),
            new LocalizedWorkoutPhrase("zh-HK", "跑步", "分鐘", true),
            new LocalizedWorkoutPhrase("pt-BR", "corrida", "minutos", false),
            new LocalizedWorkoutPhrase("vi", "chạy bộ", "phút", false),
            new LocalizedWorkoutPhrase("th", "วิ่ง", "นาที", true),
            new LocalizedWorkoutPhrase("id", "lari", "menit", false),
            new LocalizedWorkoutPhrase("hi", "दौड़ना", "मिनट", true),
            new LocalizedWorkoutPhrase("he", "ריצה", "דקות", false),
            new LocalizedWorkoutPhrase("tr", "koşu", "dakika", false),
            new LocalizedWorkoutPhrase("ar", "الجري", "دقيقة", false)
    );

    private record LocalizedWorkoutPhrase(
            String profileLocale,
            String activity,
            String minuteUnit,
            boolean allowNoSpaceAroundDuration
    ) {
        String aliasLocale() {
            return WorkoutService.normalizeAliasLocaleTag(profileLocale);
        }
    }

    private record WorkoutPlaceholderScenario(
            String profileLocale,
            String activityAlias,
            String placeholder
    ) {
        String aliasLocale() {
            return WorkoutService.normalizeAliasLocaleTag(profileLocale);
        }
    }

    private static final List<WorkoutPlaceholderScenario> WORKOUT_TRACKER_PLACEHOLDERS = List.of(
            new WorkoutPlaceholderScenario("en-US", "running", "Example: 45 min running"),
            new WorkoutPlaceholderScenario("en-US", "running", "Example: 45 min running。"),
            new WorkoutPlaceholderScenario("ja", "ランニング", "例: ランニング45分"),
            new WorkoutPlaceholderScenario("de", "laufen", "Beispiel: 45 min Laufen"),
            new WorkoutPlaceholderScenario("fr", "course", "Exemple : 45 min de course"),
            new WorkoutPlaceholderScenario("it", "corsa", "Esempio: 45 min di corsa"),
            new WorkoutPlaceholderScenario("nl", "hardlopen", "Voorbeeld: 45 min hardlopen"),
            new WorkoutPlaceholderScenario("sv", "löpning", "Exempel: 45 min löpning"),
            new WorkoutPlaceholderScenario("fi", "juoksua", "Esimerkki: 45 min juoksua"),
            new WorkoutPlaceholderScenario("pl", "biegania", "Przykład: 45 min biegania"),
            new WorkoutPlaceholderScenario("ko", "달리기", "예: 45분 달리기"),
            new WorkoutPlaceholderScenario("ko", "달리기", "예: 45분 달리기."),
            new WorkoutPlaceholderScenario("es", "correr", "Ejemplo: correr 45 min"),
            new WorkoutPlaceholderScenario("es-MX", "correr", "Ejemplo: correr 45 min"),
            new WorkoutPlaceholderScenario("zh-CN", "跑步", "范例：跑步 45 分钟"),
            new WorkoutPlaceholderScenario("zh-HK", "跑步", "範例：跑步 45 分鐘"),
            new WorkoutPlaceholderScenario("pt-BR", "corrida", "Exemplo: 45 min corrida"),
            new WorkoutPlaceholderScenario("vi", "chạy", "Ví dụ: chạy 45 min"),
            new WorkoutPlaceholderScenario("th", "วิ่ง", "ตัวอย่าง: วิ่ง 45 min"),
            new WorkoutPlaceholderScenario("id", "lari", "Contoh: lari 45 menit"),
            new WorkoutPlaceholderScenario("hi", "दौड़ना", "उदाहरण: 45 मिनट दौड़ना"),
            new WorkoutPlaceholderScenario("he", "ריצה", "דוגמה: 45 דקות ריצה"),
            new WorkoutPlaceholderScenario("tr", "koşu", "Örnek: 45 min koşu"),
            new WorkoutPlaceholderScenario("ar", "الجري", "مثال: الجري لمدة 45 دقيقة")
    );

    private static final List<String> APP_LOCALES = APP_LOCALE_WORKOUT_PHRASES.stream()
            .map(LocalizedWorkoutPhrase::profileLocale)
            .toList();

    @Autowired private WorkoutService svc;

    @Autowired private com.caloshape.backend.users.profile.repo.UserProfileRepository profileRepo;

    @Autowired private com.caloshape.backend.users.user.repo.UserRepo userRepo;

    @Autowired private JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void setUp() {
        Integer other = jdbc.queryForObject(
                "select count(*) from workout_dictionary where canonical_key = 'other_exercise'",
                Integer.class
        );
        assertNotNull(other);
        assertEquals(1, other, "workout_dictionary should contain canonical_key='other_exercise'. data.sql not applied?");

        // Create a fresh user for each test to avoid ID/data coupling.
        var user = new com.caloshape.backend.users.user.entity.User();
        user.setEmail("test-" + UUID.randomUUID() + "@local");
        user = userRepo.save(user);

        Long uid = user.getId();
        userId = uid;

        // AuthContext expects the principal to be the user id.
        var auth = new UsernamePasswordAuthenticationToken(
                uid,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Seed profile with both userId and user for the MapsId relationship.
        var p = new UserProfile();
        p.setUserId(uid);
        p.setUser(user);
        p.setWeightKg(70.0);

        profileRepo.saveAndFlush(p);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void zh_mr_30min_should_match_running_or_jogging() {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias("zh-TW", "慢跑", runningId);

        EstimateResponse r = svc.estimate("30分鐘 慢跑");
        assertEquals("ok", r.status());
        assertEquals(runningId, r.activityId());
        assertEquals(30, r.minutes());
        assertNotNull(r.kcal());
        assertTrue(r.kcal() > 0);
    }

    @Test
    void es_45min_ciclismo() {
        Long cyclingId = jdbc.queryForObject(
                "select id from workout_dictionary where canonical_key = 'cycling'",
                Long.class
        );
        assertNotNull(cyclingId);

        EstimateResponse r = svc.estimate("45 minutos ciclismo");
        assertEquals("ok", r.status());
        assertEquals(45, r.minutes());
        assertEquals(cyclingId, r.activityId());
    }

    @Test
    void hi_45_minutes_running_should_remove_duration_from_activity_name() {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias("hi", "दौड़ना", runningId);

        EstimateResponse r = svc.estimate("45 मिनट दौड़ना");

        assertEquals("ok", r.status());
        assertEquals(runningId, r.activityId());
        assertEquals("दौड़ना", r.activityDisplay());
        assertEquals(45, r.minutes());
    }

    @Test
    void ar_running_for_45_minutes_should_remove_duration_connector() {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias("ar", "الجري", runningId);

        EstimateResponse r = svc.estimate("الجري لمدة 45 دقيقة");

        assertEquals("ok", r.status());
        assertEquals(runningId, r.activityId());
        assertEquals("الجري", r.activityDisplay());
        assertEquals(45, r.minutes());
    }

    @Test
    void en_us_profile_should_use_en_alias() {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias("en-US", "en", "cardio stride", runningId);

        EstimateResponse r = svc.estimate("cardio stride 45 minutes");

        assertEquals("ok", r.status());
        assertEquals(runningId, r.activityId());
        assertEquals("cardio stride", r.activityDisplay());
        assertEquals(45, r.minutes());
    }

    @Test
    void es_mx_profile_should_use_es_alias() {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias("es-MX", "es", "carrera tranquila", runningId);

        EstimateResponse r = svc.estimate("carrera tranquila 45 minutos");

        assertEquals("ok", r.status());
        assertEquals(runningId, r.activityId());
        assertEquals("carrera tranquila", r.activityDisplay());
        assertEquals(45, r.minutes());
    }

    @Test
    void unknown_low_confidence_should_not_calculate_calories() {
        EstimateResponse r = svc.estimate("20 min flarbbbolooo");
        assertEquals("not_found", r.status());
        assertNull(r.minutes());
        assertNull(r.activityId());
        assertNull(r.kcal());
    }

    @Test
    void noisy_input_with_trusted_activity_token_should_still_match() {
        Long runningId = dictionaryId("running");

        EstimateResponse r = svc.estimate("aaa 5 min run");

        assertEquals("ok", r.status());
        assertEquals(runningId, r.activityId());
        assertEquals("aaa run", r.activityDisplay());
        assertEquals(5, r.minutes());
        assertNotNull(r.kcal());
    }

    @Test
    void noisy_input_with_duration_only_should_not_calculate_calories() {
        EstimateResponse r = svc.estimate("aaa 5 min");

        assertEquals("not_found", r.status());
        assertNull(r.minutes());
        assertNull(r.activityId());
        assertNull(r.kcal());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "5 min run",
            "aaa 5 min run",
            "5 mins run",
            "aaa 5 mins run",
            "5 min running",
            "5 mins running",
            "running 5 min",
            "running 5 mins",
            "5 minutes running",
            "running 5 minutes",
            "30 min cycling",
            "100 min cycling",
            "100 mins cycling",
            "1h 10 min cycling",
            "cycling 1h 10 min ",
            "1h 10 mins cycling",
            "cycling 1h 10 mins",
            "2hrs 10 min cycling",
            "cycling 2hrs 10 min ",
            "2hrs 10 mins cycling",
            "cycling 2hrs 10 mins",
            "1h yoga",
            "1Hr yoga",
            "2hrs yoga",
            "yoga 1h",
            "yoga 1Hr",
            "yoga 2hrs",
            "5mins running",
            "running 5mins",
            "1h10min cycling",
            "cycling 1h10min",
            "2hrs10mins cycling",
            "cycling 2hrs10mins",
            "aaa 5 min running",
            "aaa 5 mins running",
            "aaa running 5 min",
            "aaa running 5 mins",
            "aaa 5 minutes running",
            "aaa running 5 minutes",
            "aaa 30 min cycling",
            "aaa 100 min cycling",
            "aaa 100 mins cycling",
            "aaa 1h 10 min cycling",
            "aaa cycling 1h 10 min ",
            "aaa 1h 10 mins cycling",
            "aaa cycling 1h 10 mins",
            "aaa 2hrs 10 min cycling",
            "aaa cycling 2hrs 10 min ",
            "aaa 2hrs 10 mins cycling",
            "aaa cycling 2hrs 10 mins",
            "aaa 1h yoga",
            "aaa 1Hr yoga",
            "aaa 2hrs yoga",
            "aaa yoga 1h",
            "aaa yoga 1Hr",
            "aaa yoga 2hrs"
    })
    void accepted_workout_input_matrix_should_calculate(String text) {
        EstimateResponse r = svc.estimate(text);

        assertEquals("ok", r.status(), text);
        assertNotNull(r.activityId(), text);
        assertNotNull(r.minutes(), text);
        assertNotNull(r.kcal(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "5 min aaa",
            "aaa 5 min",
            "5 min flarbbbolooo",
            "running",
            "aaa",
            "5 mins aaaa",
            "aaa 5 mins",
            "5 min aa",
            "5 mins a",
            "a 5 min",
            "a 5 mins",
            "5 minutes a",
            "a 5 minutes",
            "30 min a",
            "100 min a",
            "100 mins a",
            "1h 10 min a",
            "a 1h 10 min ",
            "1h 10 mins a",
            "a 1h 10 mins",
            "2hrs 10 min a",
            "a 2hrs 10 min ",
            "2hrs 10 mins a",
            "a 2hrs 10 mins",
            "1h a",
            "1Hr a",
            "2hrs a",
            "a 1h",
            "a 1Hr",
            "aaa5 mina running",
            "aaa5 min running",
            "aaa5mins running",
            "5 mina running",
            "5minaaa running",
            "runningaaa 5 min",
            "running 5minaaa",
            "a 2hrs",
            "aaa5 分X running"
    })
    void rejected_workout_input_matrix_should_not_calculate(String text) {
        EstimateResponse r = svc.estimate(text);

        assertEquals("not_found", r.status(), text);
        assertNull(r.activityId(), text);
        assertNull(r.minutes(), text);
        assertNull(r.kcal(), text);
    }

    @ParameterizedTest
    @MethodSource("workoutTrackerExamplePlaceholderScenarios")
    void workout_tracker_example_placeholder_should_calculate(
            String profileLocale,
            String aliasLocale,
            String activityAlias,
            String placeholder
    ) {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias(profileLocale, aliasLocale, activityAlias, runningId);

        EstimateResponse r = svc.estimate(placeholder);

        assertEquals("ok", r.status(), profileLocale + " " + placeholder);
        assertEquals(runningId, r.activityId(), profileLocale + " " + placeholder);
        assertEquals(45, r.minutes(), profileLocale + " " + placeholder);
        assertNotNull(r.kcal(), profileLocale + " " + placeholder);
        assertTrue(r.kcal() > 0, profileLocale + " " + placeholder);
    }

    @ParameterizedTest
    @MethodSource("translatedAppLocaleAcceptedNewRuleScenarios")
    void app_locale_new_rule_valid_inputs_should_calculate(
            String profileLocale,
            String aliasLocale,
            String aliasPhrase,
            String text
    ) {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias(profileLocale, aliasLocale, aliasPhrase, runningId);

        EstimateResponse r = svc.estimate(text);

        assertEquals("ok", r.status(), profileLocale + " " + text);
        assertEquals(runningId, r.activityId(), profileLocale + " " + text);
        assertEquals(30, r.minutes(), profileLocale + " " + text);
        assertNotNull(r.kcal(), profileLocale + " " + text);
    }

    @ParameterizedTest
    @MethodSource("translatedAppLocaleRejectedNewRuleScenarios")
    void app_locale_new_rule_noisy_ascii_boundaries_should_not_calculate(
            String profileLocale,
            String aliasLocale,
            String aliasPhrase,
            String text
    ) {
        Long runningId = dictionaryId("running");
        setLocaleAndAlias(profileLocale, aliasLocale, aliasPhrase, runningId);

        EstimateResponse r = svc.estimate(text);

        assertEquals("not_found", r.status(), profileLocale + " " + text);
        assertNull(r.activityId(), profileLocale + " " + text);
        assertNull(r.minutes(), profileLocale + " " + text);
        assertNull(r.kcal(), profileLocale + " " + text);
    }

    static Stream<Arguments> appLocaleAcceptedNewRuleScenarios() {
        Stream<Arguments> asciiMinuteInputs = APP_LOCALES.stream()
                .flatMap(locale -> Stream.of(
                        Arguments.of(locale, WorkoutService.normalizeAliasLocaleTag(locale), "running", "running 30 min"),
                        Arguments.of(locale, WorkoutService.normalizeAliasLocaleTag(locale), "running", "30min running")
                ));

        Stream<Arguments> noSpaceNativeInputs = Stream.of(
                Arguments.of("zh-CN", "zh-CN", "跑步", "30分钟跑步"),
                Arguments.of("zh-CN", "zh-CN", "跑步", "跑步30分钟"),
                Arguments.of("zh-HK", "zh-HK", "跑步", "30分鐘跑步"),
                Arguments.of("zh-HK", "zh-HK", "跑步", "跑步30分鐘")
        );

        return Stream.concat(asciiMinuteInputs, noSpaceNativeInputs);
    }

    static Stream<Arguments> appLocaleRejectedNewRuleScenarios() {
        return APP_LOCALES.stream()
                .flatMap(locale -> Stream.of(
                        Arguments.of(locale, WorkoutService.normalizeAliasLocaleTag(locale), "running", "aaa30 min running"),
                        Arguments.of(locale, WorkoutService.normalizeAliasLocaleTag(locale), "running", "aaa30min running"),
                        Arguments.of(locale, WorkoutService.normalizeAliasLocaleTag(locale), "running", "30 mina running"),
                        Arguments.of(locale, WorkoutService.normalizeAliasLocaleTag(locale), "running", "runningaaa 30 min"),
                        Arguments.of(locale, WorkoutService.normalizeAliasLocaleTag(locale), "running", "running 30minaaa")
                ));
    }

    static Stream<Arguments> translatedAppLocaleAcceptedNewRuleScenarios() {
        return APP_LOCALE_WORKOUT_PHRASES.stream()
                .flatMap(WorkoutServiceEstimateIT::acceptedLocalizedInputs);
    }

    static Stream<Arguments> translatedAppLocaleRejectedNewRuleScenarios() {
        return APP_LOCALE_WORKOUT_PHRASES.stream()
                .flatMap(WorkoutServiceEstimateIT::rejectedLocalizedInputs);
    }

    static Stream<Arguments> workoutTrackerExamplePlaceholderScenarios() {
        return WORKOUT_TRACKER_PLACEHOLDERS.stream()
                .map(placeholder -> Arguments.of(
                        placeholder.profileLocale(),
                        placeholder.aliasLocale(),
                        placeholder.activityAlias(),
                        placeholder.placeholder()
                ));
    }

    private static Stream<Arguments> acceptedLocalizedInputs(LocalizedWorkoutPhrase phrase) {
        String activity = phrase.activity();
        String minute = phrase.minuteUnit();
        Stream<Arguments> spacedInputs = Stream.of(
                localeScenario(phrase, activity + " 30 " + minute),
                localeScenario(phrase, "30 " + minute + " " + activity),
                localeScenario(phrase, activity + " 30" + minute),
                localeScenario(phrase, "30" + minute + " " + activity)
        );
        if (!phrase.allowNoSpaceAroundDuration()) {
            return spacedInputs;
        }

        return Stream.concat(
                spacedInputs,
                Stream.of(
                        localeScenario(phrase, activity + "30" + minute),
                        localeScenario(phrase, "30" + minute + activity)
                )
        );
    }

    private static Stream<Arguments> rejectedLocalizedInputs(LocalizedWorkoutPhrase phrase) {
        String activity = phrase.activity();
        String minute = phrase.minuteUnit();
        return Stream.of(
                localeScenario(phrase, "aaa30 " + minute + " " + activity),
                localeScenario(phrase, "aaa30" + minute + " " + activity),
                localeScenario(phrase, "30 " + minute + "x " + activity),
                localeScenario(phrase, activity + "aaa 30 " + minute),
                localeScenario(phrase, activity + " 30" + minute + "aaa"),
                localeScenario(phrase, "30 " + minute + " aaa"),
                localeScenario(phrase, "aaa 30 " + minute),
                localeScenario(phrase, activity)
        );
    }

    private static Arguments localeScenario(LocalizedWorkoutPhrase phrase, String text) {
        return Arguments.of(
                phrase.profileLocale(),
                phrase.aliasLocale(),
                phrase.activity(),
                text
        );
    }

    private Long dictionaryId(String canonicalKey) {
        Long id = jdbc.queryForObject(
                "select id from workout_dictionary where canonical_key = ?",
                Long.class,
                canonicalKey
        );
        assertNotNull(id);
        return id;
    }

    private void setLocaleAndAlias(String locale, String phrase, Long dictionaryId) {
        setLocaleAndAlias(locale, locale, phrase, dictionaryId);
    }

    private void setLocaleAndAlias(
            String profileLocale,
            String aliasLocale,
            String phrase,
            Long dictionaryId
    ) {
        UserProfile profile = profileRepo.findByUserId(userId).orElseThrow();
        profile.setLocale(profileLocale);
        profileRepo.saveAndFlush(profile);

        jdbc.update(
                """
                insert into workout_alias (dictionary_id, lang_tag, phrase_lower, status, created_at)
                values (?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP)
                """,
                dictionaryId,
                aliasLocale,
                phrase
        );
    }
}
