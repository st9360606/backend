package com.calai.backend.referral.service;

import com.calai.backend.referral.dto.NotificationItemDto;
import com.calai.backend.referral.repo.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@RequiredArgsConstructor
@Service
public class NotificationInboxService {
    private final UserNotificationRepository notificationRepository;

    public List<NotificationItemDto> getMyNotifications(Long userId) {
        return notificationRepository.findTop50ByUserIdOrderByCreatedAtUtcDesc(userId)
                .stream()
                .map(n -> new NotificationItemDto(
                        n.getId(),
                        n.getType(),
                        n.getTitle(),
                        n.getMessage(),
                        n.getDeepLink(),
                        n.getCreatedAtUtc(),
                        n.isRead()
                ))
                .toList();
    }
}
