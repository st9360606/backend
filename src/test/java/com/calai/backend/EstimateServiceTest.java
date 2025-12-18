package com.calai.backend;

import com.calai.backend.workout.dto.EstimateResponse;
import com.calai.backend.workout.service.WorkoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EstimateServiceTest {

    @Autowired
    private WorkoutService svc;

    @BeforeEach
    void setUpAuth() {
        // ✅ AuthContext 期待 principal 是 Long（或 String 可轉 long）
        var auth = new UsernamePasswordAuthenticationToken(
                1L, // principal = userId
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDownAuth() {
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
        EstimateResponse r = svc.estimate("45 minutos ciclismo");
        assertEquals("ok", r.status());
        assertEquals(45, r.minutes());
    }

    @Test
    void unknown_low_confidence_go_generic() {
        EstimateResponse r = svc.estimate("20 min flarbbbolooo");
        assertEquals("ok", r.status());
        assertEquals(20, r.minutes());
    }
}
