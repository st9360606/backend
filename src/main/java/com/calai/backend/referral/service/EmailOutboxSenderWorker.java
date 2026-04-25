package com.calai.backend.referral.service;

import com.calai.backend.referral.entity.EmailOutboxEntity;
import com.calai.backend.referral.repo.EmailOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@RequiredArgsConstructor
@Slf4j
@Component
public class EmailOutboxSenderWorker {

    private final EmailOutboxRepository emailOutboxRepository;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

    @Value("${app.email.enabled:true}")
    private boolean emailEnabled;

    @Value("${app.email.sender:no-reply@bitecal.app}")
    private String sender;

    @Value("${referral.email-outbox.batch-size:20}")
    private int batchSize;

    @Value("${referral.email-outbox.max-retries:5}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${referral.email-outbox.fixed-delay:PT1M}")
    public void sendPendingEmails() {
        if (!emailEnabled) return;

        var items = emailOutboxRepository.findPendingToSend(
                maxRetries,
                PageRequest.of(0, batchSize)
        );

        for (EmailOutboxEntity item : items) {
            sendOne(item);
        }
    }

    private void sendOne(EmailOutboxEntity item) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(sender);
            message.setTo(item.getToEmail());
            message.setSubject(resolveSubject(item));
            message.setText(resolveBody(item));

            mailSender.send(message);

            item.setStatus("SENT");
            item.setSentAtUtc(Instant.now());
            emailOutboxRepository.save(item);

            log.info("referral_email_outbox_sent id={} to={}", item.getId(), item.getToEmail());
        } catch (Exception ex) {
            int nextRetry = item.getRetryCount() == null ? 1 : item.getRetryCount() + 1;
            item.setRetryCount(nextRetry);

            if (nextRetry >= maxRetries) {
                item.setStatus("FAILED");
            } else {
                item.setStatus("PENDING");
            }

            emailOutboxRepository.save(item);

            log.warn(
                    "referral_email_outbox_send_failed id={} retryCount={} status={} error={}",
                    item.getId(),
                    item.getRetryCount(),
                    item.getStatus(),
                    ex.toString()
            );
        }
    }

    private String resolveSubject(EmailOutboxEntity item) {
        return switch (item.getTemplateType()) {
            case "REFERRAL_GRANTED" -> "Your BiteCal referral reward was granted";
            case "REFERRAL_REJECTED" -> "Your BiteCal referral did not qualify";
            default -> "BiteCal notification";
        };
    }

    private String resolveBody(EmailOutboxEntity item) throws Exception {
        JsonNode payload = objectMapper.readTree(item.getTemplatePayloadJson());

        return switch (item.getTemplateType()) {
            case "REFERRAL_GRANTED" -> buildGrantedBody(payload);
            case "REFERRAL_REJECTED" -> buildRejectedBody(payload);
            default -> "You have a new BiteCal notification.";
        };
    }

    private String buildGrantedBody(JsonNode payload) {
        String oldPremiumUntil = payload.path("oldPremiumUntil").asText("—");
        String newPremiumUntil = payload.path("newPremiumUntil").asText("—");
        String grantedAtUtc = payload.path("grantedAtUtc").asText("—");
        int daysAdded = payload.path("daysAdded").asInt(30);

        return """
                Great news!

                Your referral reward has been granted.

                Reward:
                - Premium extended by %d days
                - Old expiry: %s
                - New expiry: %s
                - Granted at: %s

                You can view the details in BiteCal > Premium & Rewards.
                """.formatted(daysAdded, oldPremiumUntil, newPremiumUntil, grantedAtUtc);
    }

    private String buildRejectedBody(JsonNode payload) {
        String reason = payload.path("reason").asText("This referral did not qualify for a reward");

        return """
                Your referral did not qualify for a reward.

                Reason:
                %s

                You can view the details in BiteCal > Referrals.
                """.formatted(reason);
    }
}
