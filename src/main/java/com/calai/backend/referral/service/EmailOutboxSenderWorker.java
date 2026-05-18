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
        if (!emailEnabled) {
            return;
        }

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

            log.info("referral_email_outbox_sent id={}", item.getId());
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

    private String resolveSubject(EmailOutboxEntity item) throws Exception {
        String templateType = firstNonBlank(item.getTemplateType(), "");

        return switch (templateType) {
            case "REFERRAL_GRANTED" -> buildGrantedSubject(item);
            case "REFERRAL_REJECTED" -> "Update on your BiteCal referral";
            default -> "BiteCal notification";
        };
    }

    private String buildGrantedSubject(EmailOutboxEntity item) throws Exception {
        JsonNode payload = objectMapper.readTree(item.getTemplatePayloadJson());
        int daysAdded = payload.path("daysAdded").asInt(30);
        return "You earned %d free days of BiteCal Premium 🎉".formatted(daysAdded);
    }

    private String resolveBody(EmailOutboxEntity item) throws Exception {
        JsonNode payload = objectMapper.readTree(item.getTemplatePayloadJson());
        String templateType = firstNonBlank(item.getTemplateType(), "");

        return switch (templateType) {
            case "REFERRAL_GRANTED" -> buildGrantedBody(payload);
            case "REFERRAL_REJECTED" -> buildRejectedBody(payload);
            default -> "You have a new BiteCal notification.";
        };
    }

    private String buildGrantedBody(JsonNode payload) {
        String oldPremiumUntil = firstNonBlank(
                payload.path("oldPremiumUntilLocal").asText(""),
                payload.path("oldPremiumUntil").asText(""),
                "No active Premium expiry"
        );
        String newPremiumUntil = firstNonBlank(
                payload.path("newPremiumUntilLocal").asText(""),
                payload.path("newPremiumUntil").asText(""),
                "Not available"
        );
        String grantedAt = firstNonBlank(
                payload.path("grantedAtLocal").asText(""),
                payload.path("grantedAtUtc").asText(""),
                "Not available"
        );
        int daysAdded = payload.path("daysAdded").asInt(30);

        return """
                Great news!

                Your referral reward has been granted successfully.

                You've earned %d free days of BiteCal Premium because your invited friend completed a valid subscription.

                Reward summary:
                - Reward: Premium extended by %d days 🎁
                - Previous expiry: %s
                - New expiry: %s
                - Granted at: %s

                You can view your referral reward details in:

                BiteCal → Settings → Inbox

                Thanks for sharing BiteCal with your friends. Keep inviting to earn more Premium days! 💪

                The BiteCal Team
                """.formatted(daysAdded, daysAdded, oldPremiumUntil, newPremiumUntil, grantedAt);
    }

    private String buildRejectedBody(JsonNode payload) {
        String reason = firstNonBlank(
                payload.path("reason").asText(""),
                "This referral could not be rewarded this time"
        );

        return """
            Hi there,

            Thanks for sharing BiteCal with your friend.

            We reviewed this referral, but we couldn’t apply a Premium reward this time.

            Reason:
            %s

            You can still earn 30 free Premium days when an invited friend completes a valid subscription.

            You can view the details in:

            BiteCal → Settings → Inbox

            Thanks again for helping BiteCal grow.

            The BiteCal Team
            """.formatted(reason);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
