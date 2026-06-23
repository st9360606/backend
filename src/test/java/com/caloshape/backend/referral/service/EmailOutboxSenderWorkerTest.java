package com.caloshape.backend.referral.service;

import com.caloshape.backend.referral.entity.EmailOutboxEntity;
import com.caloshape.backend.referral.repo.EmailOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailOutboxSenderWorkerTest {

    @Mock
    private EmailOutboxRepository emailOutboxRepository;

    @Mock
    private JavaMailSender mailSender;

    private EmailOutboxSenderWorker worker;

    @BeforeEach
    void setUp() {
        worker = new EmailOutboxSenderWorker(
                emailOutboxRepository,
                mailSender,
                new ObjectMapper().findAndRegisterModules()
        );

        ReflectionTestUtils.setField(worker, "emailEnabled", true);
        ReflectionTestUtils.setField(worker, "sender", "no-reply@caloshape.app");
        ReflectionTestUtils.setField(worker, "batchSize", 20);
        ReflectionTestUtils.setField(worker, "maxRetries", 5);
    }

    @Test
    void sendPendingEmails_shouldMarkPendingEmailAsSentAfterSuccessfulSend() {
        EmailOutboxEntity item = pendingItem();
        when(emailOutboxRepository.findPendingToSend(eq(5), any(Pageable.class))).thenReturn(List.of(item));

        worker.sendPendingEmails();

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@caloshape.app");
        assertThat(message.getTo()).containsExactly("inviter@example.com");
        assertThat(message.getSubject()).isEqualTo("You earned 30 free days of CaloShape Premium 🎉");
        assertThat(message.getText())
                .contains("Great news!")
                .contains("Your referral reward has been granted successfully.")
                .contains("You've earned 30 free days of CaloShape Premium because your invited friend completed a valid subscription.")
                .contains("- Reward: Premium extended by 30 days 🎁")
                .contains("- Previous expiry: 2026-05-01 08:00 (Asia/Taipei, UTC+08:00)")
                .contains("- Granted at: 2026-05-10 08:00 (Asia/Taipei, UTC+08:00)")
                .contains("- New expiry: 2026-05-31 08:00 (Asia/Taipei, UTC+08:00)")
                .contains("CaloShape → Settings → Inbox")
                .contains("Thanks for sharing CaloShape with your friends. Keep inviting to earn more Premium days! 💪");

        verify(emailOutboxRepository).save(item);
        assertThat(item.getStatus()).isEqualTo("SENT");
        assertThat(item.getSentAtUtc()).isNotNull();
    }

    @Test
    void sendPendingEmails_shouldIncrementRetryAndKeepPendingBeforeMaxRetries() {
        EmailOutboxEntity item = pendingItem();
        item.setRetryCount(3);

        when(emailOutboxRepository.findPendingToSend(eq(5), any(Pageable.class))).thenReturn(List.of(item));
        doThrow(new RuntimeException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));

        worker.sendPendingEmails();

        verify(emailOutboxRepository).save(item);
        assertThat(item.getRetryCount()).isEqualTo(4);
        assertThat(item.getStatus()).isEqualTo("PENDING");
        assertThat(item.getSentAtUtc()).isNull();
    }

    @Test
    void sendPendingEmails_shouldMarkFailedWhenRetryCountReachesMaxRetries() {
        EmailOutboxEntity item = pendingItem();
        item.setRetryCount(4);

        when(emailOutboxRepository.findPendingToSend(eq(5), any(Pageable.class))).thenReturn(List.of(item));
        doThrow(new RuntimeException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));

        worker.sendPendingEmails();

        verify(emailOutboxRepository).save(item);
        assertThat(item.getRetryCount()).isEqualTo(5);
        assertThat(item.getStatus()).isEqualTo("FAILED");
        assertThat(item.getSentAtUtc()).isNull();
    }

    @Test
    void sendPendingEmails_shouldFallbackToRawUtcFieldsWhenLocalFieldsAreMissing() {
        EmailOutboxEntity item = pendingItemWithoutLocalFields();
        when(emailOutboxRepository.findPendingToSend(eq(5), any(Pageable.class))).thenReturn(List.of(item));

        worker.sendPendingEmails();

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("- Previous expiry: 2026-05-01T00:00:00Z")
                .contains("- New expiry: 2026-05-31T00:00:00Z")
                .contains("- Granted at: 2026-05-10T00:00:00Z");
    }

    private EmailOutboxEntity pendingItem() {
        EmailOutboxEntity item = basePendingItem();
        item.setTemplatePayloadJson("""
                {
                  "claimId": 10,
                  "daysAdded": 30,
                  "oldPremiumUntil": "2026-05-01T00:00:00Z",
                  "newPremiumUntil": "2026-05-31T00:00:00Z",
                  "grantedAtUtc": "2026-05-10T00:00:00Z",
                  "timezone": "Asia/Taipei",
                  "oldPremiumUntilLocal": "2026-05-01 08:00 (Asia/Taipei, UTC+08:00)",
                  "newPremiumUntilLocal": "2026-05-31 08:00 (Asia/Taipei, UTC+08:00)",
                  "newExpiryDate": "2026-05-31",
                  "grantedAtLocal": "2026-05-10 08:00 (Asia/Taipei, UTC+08:00)"
                }
                """);
        return item;
    }

    private EmailOutboxEntity pendingItemWithoutLocalFields() {
        EmailOutboxEntity item = basePendingItem();
        item.setTemplatePayloadJson("""
                {
                  "claimId": 10,
                  "daysAdded": 30,
                  "oldPremiumUntil": "2026-05-01T00:00:00Z",
                  "newPremiumUntil": "2026-05-31T00:00:00Z",
                  "newExpiryDate": "2026-05-31",
                  "grantedAtUtc": "2026-05-10T00:00:00Z"
                }
                """);
        return item;
    }

    private EmailOutboxEntity basePendingItem() {
        EmailOutboxEntity item = new EmailOutboxEntity();
        item.setId(1L);
        item.setUserId(100L);
        item.setToEmail("inviter@example.com");
        item.setTemplateType("REFERRAL_GRANTED");
        item.setDedupeKey("referral:granted:10");
        item.setRetryCount(0);
        item.setStatus("PENDING");
        return item;
    }
}
