package com.caloshape.backend.referral.service;

import com.caloshape.backend.referral.dto.NotificationItemDto;
import com.caloshape.backend.referral.dto.NotificationMarkReadResponseDto;
import com.caloshape.backend.referral.repo.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@RequiredArgsConstructor
@Service
public class NotificationInboxService {
    private final UserNotificationRepository notificationRepository;

    @Transactional(readOnly = true)
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

    @Transactional
    public NotificationMarkReadResponseDto markRead(Long userId, Long notificationId) {
        var notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NoSuchElementException("NOTIFICATION_NOT_FOUND"));

        if (!notification.isRead()) {
            notification.setRead(true);
        }

        return new NotificationMarkReadResponseDto(notification.getId(), notification.isRead());
    }
}
