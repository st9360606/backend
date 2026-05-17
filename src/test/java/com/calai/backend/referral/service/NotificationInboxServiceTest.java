package com.calai.backend.referral.service;

import com.calai.backend.referral.entity.UserNotificationEntity;
import com.calai.backend.referral.repo.UserNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationInboxServiceTest {

    @Mock
    private UserNotificationRepository notificationRepository;

    @InjectMocks
    private NotificationInboxService service;

    @Test
    void getMyNotifications_shouldUseOwnTop50CreatedAtDescQueryAndMapResponse() {
        UserNotificationEntity newest = notification(
                2L,
                100L,
                "GRANTED",
                "Reward granted",
                "Premium extended",
                "bitecal://premium-rewards",
                Instant.parse("2026-05-16T00:00:00Z"),
                false
        );
        UserNotificationEntity older = notification(
                1L,
                100L,
                "REJECTED",
                "Reward not granted",
                "Referral did not qualify",
                "bitecal://referrals",
                Instant.parse("2026-05-15T00:00:00Z"),
                true
        );
        when(notificationRepository.findTop50ByUserIdOrderByCreatedAtUtcDesc(100L))
                .thenReturn(List.of(newest, older));

        var result = service.getMyNotifications(100L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(2L);
        assertThat(result.get(0).read()).isFalse();
        assertThat(result.get(1).id()).isEqualTo(1L);
        assertThat(result.get(1).read()).isTrue();
        verify(notificationRepository).findTop50ByUserIdOrderByCreatedAtUtcDesc(100L);
    }

    @Test
    void markRead_shouldMarkOwnNotificationAsRead() {
        UserNotificationEntity notification = notification(
                10L,
                100L,
                "GRANTED",
                "Reward granted",
                "Premium extended",
                null,
                Instant.parse("2026-05-16T00:00:00Z"),
                false
        );
        when(notificationRepository.findByIdAndUserId(10L, 100L)).thenReturn(Optional.of(notification));

        var response = service.markRead(100L, 10L);

        assertThat(notification.isRead()).isTrue();
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.read()).isTrue();
    }

    @Test
    void markRead_shouldBeIdempotentWhenAlreadyRead() {
        UserNotificationEntity notification = notification(
                10L,
                100L,
                "GRANTED",
                "Reward granted",
                "Premium extended",
                null,
                Instant.parse("2026-05-16T00:00:00Z"),
                true
        );
        when(notificationRepository.findByIdAndUserId(10L, 100L)).thenReturn(Optional.of(notification));

        var response = service.markRead(100L, 10L);

        assertThat(notification.isRead()).isTrue();
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.read()).isTrue();
    }

    @Test
    void markRead_shouldReturnNotFoundForMissingOrOtherUsersNotification() {
        when(notificationRepository.findByIdAndUserId(10L, 100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(100L, 10L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("NOTIFICATION_NOT_FOUND");
        verify(notificationRepository, never()).save(any(UserNotificationEntity.class));
    }

    private UserNotificationEntity notification(
            Long id,
            Long userId,
            String type,
            String title,
            String message,
            String deepLink,
            Instant createdAtUtc,
            boolean read
    ) {
        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setMessage(message);
        entity.setDeepLink(deepLink);
        entity.setSourceType("REFERRAL_CLAIM");
        entity.setSourceRefId(99L);
        entity.setCreatedAtUtc(createdAtUtc);
        entity.setRead(read);
        return entity;
    }
}
