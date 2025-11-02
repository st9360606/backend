package com.calai.backend;

import com.calai.backend.workout.service.WorkoutService;
import com.calai.backend.workout.dto.EstimateResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EstimateServiceTest {

    // 假設你用 @SpringBootTest 建立；此處簡化展示
    private WorkoutService svc; // TODO: 注入 mocks

    EstimateServiceTest(WorkoutService svc) {
        this.svc = svc;
    }

    @Test
    void zh_mr_30min_should_match_running_or_jogging() {
        EstimateResponse r = svc.estimate("30分鐘 慢跑");
        assertEquals("ok", r.status());
        assertNotNull(r.activityId());
        assertEquals(30, r.minutes());
        assertTrue(r.kcal() != null && r.kcal() > 0);
    }

    @Test
    void es_45min_ciclismo() {
        var r = svc.estimate("45 minutos ciclismo");
        assertEquals("ok", r.status());
        assertEquals(45, r.minutes());
    }

    @Test
    void unknown_low_confidence_go_generic() {
        var r = svc.estimate("20 min flarbbbolooo"); // 無意義字
        assertEquals("ok", r.status());
        assertEquals(20, r.minutes());
    }
}
