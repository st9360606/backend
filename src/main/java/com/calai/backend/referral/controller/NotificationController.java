package com.calai.backend.referral.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.referral.dto.NotificationItemDto;
import com.calai.backend.referral.dto.NotificationMarkReadRequestDto;
import com.calai.backend.referral.dto.NotificationMarkReadResponseDto;
import com.calai.backend.referral.service.NotificationInboxService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final AuthContext authContext;
    private final NotificationInboxService notificationInboxService;

    @GetMapping
    public List<NotificationItemDto> list() {
        return notificationInboxService.getMyNotifications(authContext.requireUserId());
    }

    @PatchMapping("/{notificationId}/read")
    public NotificationMarkReadResponseDto markRead(
            @PathVariable @Positive Long notificationId,
            @Valid @RequestBody(required = false) NotificationMarkReadRequestDto ignored
    ) {
        return notificationInboxService.markRead(authContext.requireUserId(), notificationId);
    }
}
