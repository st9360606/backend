package com.calai.backend.referral.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.referral.dto.NotificationItemDto;
import com.calai.backend.referral.service.NotificationInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
