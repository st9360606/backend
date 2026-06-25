package com.caloshape.backend.referral.controller;

import com.caloshape.backend.auth.repo.AuthTokenRepo;
import com.caloshape.backend.auth.security.AuthContext;
import com.caloshape.backend.common.web.ApiExceptionHandler;
import com.caloshape.backend.referral.dto.NotificationItemDto;
import com.caloshape.backend.referral.dto.NotificationMarkReadResponseDto;
import com.caloshape.backend.referral.service.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(NotificationController.class)
@Import(ApiExceptionHandler.class)
class NotificationControllerWebMvcTest {

    private static final Long USER_ID = 100L;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthContext authContext;

    @MockitoBean
    private AuthTokenRepo authTokenRepo;

    @MockitoBean
    private NotificationInboxService notificationInboxService;

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void list_shouldReturnOwnNotifications() throws Exception {
        when(authContext.requireUserId()).thenReturn(USER_ID);
        when(notificationInboxService.getMyNotifications(USER_ID)).thenReturn(List.of(
                new NotificationItemDto(
                        2L,
                        "GRANTED",
                        "Reward granted",
                        "Premium extended",
                        "caloshape://premium-rewards",
                        Instant.parse("2026-05-16T00:00:00Z"),
                        false
                )
        ));

        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2L))
                .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    void list_withoutLogin_shouldReturn401() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());

        verify(notificationInboxService, never()).getMyNotifications(USER_ID);
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void markRead_shouldReturnReadTrue() throws Exception {
        when(authContext.requireUserId()).thenReturn(USER_ID);
        when(notificationInboxService.markRead(USER_ID, 10L))
                .thenReturn(new NotificationMarkReadResponseDto(10L, true));

        mvc.perform(patch("/api/v1/notifications/{notificationId}/read", 10L).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void markRead_missingOrOtherUsersNotification_shouldReturn404() throws Exception {
        when(authContext.requireUserId()).thenReturn(USER_ID);
        when(notificationInboxService.markRead(USER_ID, 404L))
                .thenThrow(new NoSuchElementException("NOTIFICATION_NOT_FOUND"));

        mvc.perform(patch("/api/v1/notifications/{notificationId}/read", 404L).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void markRead_withoutLogin_shouldReturn401() throws Exception {
        mvc.perform(patch("/api/v1/notifications/{notificationId}/read", 10L).with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(notificationInboxService, never()).markRead(USER_ID, 10L);
    }
}
