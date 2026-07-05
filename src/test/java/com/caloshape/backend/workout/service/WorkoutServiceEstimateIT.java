package com.caloshape.backend.workout.service;

import com.caloshape.backend.users.profile.entity.UserProfile;
import com.caloshape.backend.workout.dto.EstimateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional // ✅ 核心：@BeforeEach + test 同一個 Persistence Context（預設會 rollback）
class WorkoutServiceEstimateIT {

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

        // ✅ 每次都建一個新 user，避免撞到既有資料/ID 假設
        var user = new com.caloshape.backend.users.user.entity.User();
        user.setEmail("test-" + UUID.randomUUID() + "@local");
        user = userRepo.save(user); // 在同一個 @Transactional 內，仍是 managed

        Long uid = user.getId();
        userId = uid;

        // ✅ AuthContext 期待 principal 是 userId
        var auth = new UsernamePasswordAuthenticationToken(
                uid,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        // ✅ seed profile（MapsId：建議同時 set userId + user）
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
        EstimateResponse r = svc.estimate("30分鐘 慢跑");
        assertEquals("ok", r.status());
        assertNotNull(r.activityId());
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
        assertEquals(cyclingId, r.activityId()); // ✅ 防止走 fallback 也過
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
    void unknown_low_confidence_go_other_exercise() {
        Long otherId = jdbc.queryForObject(
                "select id from workout_dictionary where canonical_key = 'other_exercise'",
                Long.class
        );
        assertNotNull(otherId);

        EstimateResponse r = svc.estimate("20 min flarbbbolooo");
        assertEquals("ok", r.status());
        assertEquals(20, r.minutes());
        assertEquals(otherId, r.activityId()); // ✅ 低信心應走 other_exercise
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
                insert into workout_alias (dictionary_id, lang_tag, phrase_lower, status)
                values (?, ?, ?, 'APPROVED')
                """,
                dictionaryId,
                aliasLocale,
                phrase
        );
    }
}
