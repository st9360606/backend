package com.caloshape.backend.workout.web;

import com.caloshape.backend.auth.security.AccessTokenFilter;
import com.caloshape.backend.auth.security.AuthContext;
import com.caloshape.backend.common.web.RequestIdFilter;
import com.caloshape.backend.entitlement.service.EntitlementService;
import com.caloshape.backend.entitlement.service.PremiumFeatureGateService;
import com.caloshape.backend.workout.controller.WorkoutController;
import com.caloshape.backend.workout.dto.EstimateResponse;
import com.caloshape.backend.workout.dto.LogWorkoutResponse;
import com.caloshape.backend.workout.dto.TodayWorkoutResponse;
import com.caloshape.backend.workout.dto.WorkoutHistoryResponse;
import com.caloshape.backend.workout.dto.WorkoutHistorySessionDto;
import com.caloshape.backend.workout.dto.WorkoutSessionDto;
import com.caloshape.backend.workout.service.WorkoutService;
import com.caloshape.backend.workout.web.advice.WorkoutExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(
        controllers = WorkoutController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AccessTokenFilter.class)
        }
)
@Import({PremiumFeatureGateService.class, WorkoutExceptionAdvice.class, RequestIdFilter.class})
class WorkoutPremiumGateApiTest {

    private static final Long USER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-05-15T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthContext authContext;

    @MockitoBean
    private WorkoutService workoutService;

    @MockitoBean
    private EntitlementService entitlementService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(authContext.requireUserId()).thenReturn(USER_ID);
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void freeUser_estimate_shouldReturn402SubscriptionRequired() throws Exception {
        when(entitlementService.hasActiveEntitlement(USER_ID, NOW)).thenReturn(false);

        mvc.perform(post("/api/v1/workouts/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"running 30 min\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"))
                .andExpect(jsonPath("$.errorCode").value("SUBSCRIPTION_REQUIRED"))
                .andExpect(jsonPath("$.clientAction").value("SHOW_PAYWALL"));
    }

    @Test
    void freeUser_log_shouldReturn402SubscriptionRequired() throws Exception {
        when(entitlementService.hasActiveEntitlement(USER_ID, NOW)).thenReturn(false);

        mvc.perform(post("/api/v1/workouts/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":1,\"minutes\":30}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));
    }

    @Test
    void trialActiveUser_log_shouldPassGate() throws Exception {
        when(entitlementService.hasActiveEntitlement(USER_ID, NOW)).thenReturn(true);
        when(workoutService.log(any(), any())).thenReturn(logResponse());

        mvc.perform(post("/api/v1/workouts/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Timezone", "Asia/Taipei")
                        .content("{\"activityId\":1,\"minutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSession.id").value(10));
    }

    @Test
    void premiumActiveUser_log_shouldPassGate() throws Exception {
        when(entitlementService.hasActiveEntitlement(USER_ID, NOW)).thenReturn(true);
        when(workoutService.log(any(), any())).thenReturn(logResponse());

        mvc.perform(post("/api/v1/workouts/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":1,\"minutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSession.id").value(10));
    }

    @ParameterizedTest(name = "{0} user should be blocked")
    @EnumSource(value = BlockedEntitlementCase.class)
    void blockedEntitlementStates_log_shouldReturn402(BlockedEntitlementCase ignored) throws Exception {
        when(entitlementService.hasActiveEntitlement(USER_ID, NOW)).thenReturn(false);

        mvc.perform(post("/api/v1/workouts/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":1,\"minutes\":30}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));
    }

    @Test
    void gracePeriodWithinValidWindow_log_shouldPassGate() throws Exception {
        when(entitlementService.hasActiveEntitlement(USER_ID, NOW)).thenReturn(true);
        when(workoutService.log(any(), any())).thenReturn(logResponse());

        mvc.perform(post("/api/v1/workouts/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":1,\"minutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSession.id").value(10));
    }

    @Test
    void today_shouldNotBeBlockedForFreeUser() throws Exception {
        when(workoutService.today(any())).thenReturn(new TodayWorkoutResponse(0, List.of()));

        mvc.perform(get("/api/v1/workouts/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalKcalToday").value(0));

        verify(entitlementService, never()).hasActiveEntitlement(eq(USER_ID), any());
    }

    @Test
    void recentHistory_shouldNotBeBlockedForFreeUser() throws Exception {
        when(workoutService.recentHistory(any())).thenReturn(new WorkoutHistoryResponse(
                270,
                List.of(new WorkoutHistorySessionDto(10L, "Running", 30, 270, LocalDate.parse("2026-05-15"), "May 15", "08:00"))
        ));

        mvc.perform(get("/api/v1/workouts/history/recent")
                        .header("X-Client-Timezone", "Asia/Taipei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalKcal").value(270))
                .andExpect(jsonPath("$.sessions[0].localDate").value("2026-05-15"))
                .andExpect(jsonPath("$.sessions[0].timeLabel").value("08:00"));

        verify(entitlementService, never()).hasActiveEntitlement(eq(USER_ID), any());
    }

    @Test
    void delete_shouldNotBeBlockedForFreeUser() throws Exception {
        when(workoutService.deleteSession(eq(99L), any())).thenReturn(new TodayWorkoutResponse(0, List.of()));

        mvc.perform(delete("/api/v1/workouts/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalKcalToday").value(0));

        verify(entitlementService, never()).hasActiveEntitlement(eq(USER_ID), any());
    }

    @Test
    void premiumUser_estimate_shouldPassGate() throws Exception {
        when(entitlementService.hasActiveEntitlement(USER_ID, NOW)).thenReturn(true);
        when(workoutService.estimate("running 30 min"))
                .thenReturn(new EstimateResponse("ok", 1L, "running", 30, 270));

        mvc.perform(post("/api/v1/workouts/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"running 30 min\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    private static LogWorkoutResponse logResponse() {
        return new LogWorkoutResponse(
                new WorkoutSessionDto(10L, "Running", 30, 270, "08:00"),
                new TodayWorkoutResponse(270, List.of())
        );
    }

    private enum BlockedEntitlementCase {
        EXPIRED,
        REVOKED,
        ON_HOLD
    }
}

