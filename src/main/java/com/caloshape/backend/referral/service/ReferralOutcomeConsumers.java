package com.caloshape.backend.referral.service;

import com.caloshape.backend.referral.domain.ReferralClaimStatus;
import com.caloshape.backend.referral.domain.ReferralOutcomeType;
import com.caloshape.backend.referral.entity.EmailOutboxEntity;
import com.caloshape.backend.referral.entity.ReferralCaseSnapshotEntity;
import com.caloshape.backend.referral.entity.UserNotificationEntity;
import com.caloshape.backend.referral.repo.EmailOutboxRepository;
import com.caloshape.backend.referral.repo.ReferralCaseSnapshotRepository;
import com.caloshape.backend.referral.repo.ReferralClaimRepository;
import com.caloshape.backend.referral.repo.UserNotificationRepository;
import com.caloshape.backend.users.profile.repo.UserProfileRepository;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
@Component
public class ReferralOutcomeConsumers {

    private static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;
    private static final int REFERRAL_REWARD_DAYS = 30;
    private static final String REFERRAL_SOURCE_TYPE = "REFERRAL_CLAIM";
    private static final String INBOX_DEEP_LINK = "caloshape://settings/inbox";
    private static final String INBOX_REFERRAL_DEEP_LINK_PREFIX = "caloshape://settings/inbox/referral/";

    private static final DateTimeFormatter LOCAL_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withLocale(Locale.ENGLISH);

    private static final DateTimeFormatter LOCAL_DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.ENGLISH);

    private final UserNotificationRepository notificationRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final ReferralCaseSnapshotRepository caseSnapshotRepository;
    private final ReferralClaimRepository claimRepository;
    private final UserRepo userRepo;
    private final UserProfileRepository userProfileRepository;
    private final MembershipSummaryService membershipSummaryService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(fallbackExecution = true)
    public void onOutcome(ReferralOutcomeEvent event) {
        createNotification(event);
        createEmailOutbox(event);
        refreshSnapshot(event.inviterUserId());
    }

    private void createNotification(ReferralOutcomeEvent event) {
        if (notificationRepository.existsByUserIdAndSourceTypeAndSourceRefId(
                event.inviterUserId(),
                REFERRAL_SOURCE_TYPE,
                event.claimId()
        )) {
            return;
        }

        ZoneId userZone = resolveUserZone(event.inviterUserId());

        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setUserId(event.inviterUserId());
        entity.setType(event.outcomeType().name());
        entity.setSourceType(REFERRAL_SOURCE_TYPE);
        entity.setSourceRefId(event.claimId());

        if (event.outcomeType() == ReferralOutcomeType.GRANTED) {
            entity.setTitle(buildGrantedNotificationTitle());
            entity.setMessage(buildGrantedNotificationMessage(event, userZone));
        } else {
            entity.setTitle(buildRejectedNotificationTitle());
            entity.setMessage(buildRejectedNotificationMessage(event));
        }

        entity.setDeepLink(buildInboxReferralDeepLink(event.claimId()));
        notificationRepository.save(entity);
    }

    private String buildGrantedNotificationTitle() {
        return "🎉 30 free Premium days unlocked";
    }

    private String buildGrantedNotificationMessage(ReferralOutcomeEvent event, ZoneId userZone) {
        String newExpiryDate = formatLocalDate(event.newPremiumUntil(), userZone);

        if (newExpiryDate.isBlank()) {
            return "Your CaloShape Premium has been extended by 30 days.";
        }

        return "Your CaloShape Premium has been extended by 30 days.\nNew expiry: " + newExpiryDate;
    }

    private String buildRejectedNotificationTitle() {
        return "Referral reward not granted";
    }

    private String buildRejectedNotificationMessage(ReferralOutcomeEvent event) {
        String reason = sanitizeReason(event.rejectReason());

        if (reason.isBlank()) {
            return "This referral did not qualify for a reward.";
        }

        return "This referral did not qualify.\nReason: " + reason + ".";
    }

    private String buildInboxReferralDeepLink(Long claimId) {
        if (claimId == null) {
            return INBOX_DEEP_LINK;
        }
        return INBOX_REFERRAL_DEEP_LINK_PREFIX + claimId;
    }

    private void createEmailOutbox(ReferralOutcomeEvent event) {
        if (event.outcomeType() != ReferralOutcomeType.GRANTED) {
            return;
        }

        String email = userRepo.findById(event.inviterUserId())
                .map(User::getEmail)
                .orElse(null);

        if (email == null || email.isBlank()) {
            return;
        }

        String dedupeKey = "referral:granted:" + event.claimId();
        if (emailOutboxRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }

        EmailOutboxEntity outbox = new EmailOutboxEntity();
        outbox.setUserId(event.inviterUserId());
        outbox.setToEmail(email);
        outbox.setTemplateType("REFERRAL_GRANTED");
        outbox.setDedupeKey(dedupeKey);

        try {
            ZoneId userZone = resolveUserZone(event.inviterUserId());
            outbox.setTemplatePayloadJson(buildGrantedPayload(event, userZone));
            emailOutboxRepository.save(outbox);
        } catch (DataIntegrityViolationException ex) {
            log.info("referral_granted_email_outbox_dedupe_race claimId={}", event.claimId());
        } catch (JsonProcessingException ex) {
            log.warn("referral_granted_email_outbox_payload_failed claimId={} error={}", event.claimId(), ex.toString());
        } catch (RuntimeException ex) {
            log.warn("referral_granted_email_outbox_create_failed claimId={} error={}", event.claimId(), ex.toString());
        }
    }

    private void refreshSnapshot(Long inviterUserId) {
        long successCount = claimRepository.countByInviterUserIdAndStatus(inviterUserId, "SUCCESS");
        long pendingCount = claimRepository.countByInviterUserIdAndStatusIn(inviterUserId, pendingStatuses());
        long rejectedCount = claimRepository.countByInviterUserIdAndStatusIn(inviterUserId, rejectedStatuses());
        long totalInvited = successCount + pendingCount + rejectedCount;

        int totalRewardedDays = membershipSummaryService.getRewardHistory(inviterUserId)
                .stream()
                .filter(it -> "SUCCESS".equalsIgnoreCase(it.grantStatus()) || "GRANTED".equalsIgnoreCase(it.grantStatus()))
                .mapToInt(it -> it.daysAdded() == null ? 0 : it.daysAdded())
                .sum();

        ReferralCaseSnapshotEntity snapshot = caseSnapshotRepository.findByInviterUserId(inviterUserId)
                .orElseGet(ReferralCaseSnapshotEntity::new);

        snapshot.setInviterUserId(inviterUserId);
        snapshot.setTotalInvited((int) totalInvited);
        snapshot.setSuccessCount((int) successCount);
        snapshot.setRejectedCount((int) rejectedCount);
        snapshot.setPendingVerificationCount((int) pendingCount);
        snapshot.setTotalRewardedDays(totalRewardedDays);
        snapshot.setCurrentPremiumUntil(membershipSummaryService.getMembershipSummary(inviterUserId).currentPremiumUntil());

        caseSnapshotRepository.save(snapshot);
    }

    private List<String> pendingStatuses() {
        return List.of(
                ReferralClaimStatus.PENDING_SUBSCRIPTION.name(),
                ReferralClaimStatus.PENDING_COOLDOWN.name(),
                ReferralClaimStatus.PENDING_VERIFICATION.name(),
                ReferralClaimStatus.PROCESSING_REWARD.name()
        );
    }

    private List<String> rejectedStatuses() {
        return List.of(
                ReferralClaimStatus.REJECTED.name(),
                ReferralClaimStatus.EXPIRED.name()
        );
    }

    private String sanitizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "This referral did not qualify for a reward";
        }

        return switch (raw) {
            case "REFUNDED_OR_REVOKED", "CHARGEBACK" -> "Payment was refunded or revoked";
            case "ABUSE_RISK", "RISK_REJECTED" -> "This referral did not meet verification requirements";
            case "INVITEE_ALREADY_SUBSCRIBED" -> "This referral did not qualify because the invited account had already subscribed before";
            case "PURCHASE_PENDING" -> "The subscription payment was not completed";
            case "TEST_PURCHASE" -> "Test purchases do not qualify for referral rewards";
            case "PURCHASE_NOT_VERIFIED" -> "The subscription payment could not be verified";
            case "REWARD_GRANT_FAILED" -> "We could not apply the referral reward";
            case "VERIFICATION_WINDOW_EXPIRED" -> "The invited account did not subscribe in time";
            default -> "This referral did not qualify for a reward";
        };
    }

    private String buildGrantedPayload(ReferralOutcomeEvent event, ZoneId userZone) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claimId", event.claimId());
        payload.put("daysAdded", REFERRAL_REWARD_DAYS);

        // Raw UTC values: keep for audit/debug/backward compatibility.
        payload.put("oldPremiumUntil", formatInstant(event.oldPremiumUntil()));
        payload.put("newPremiumUntil", formatInstant(event.newPremiumUntil()));
        payload.put("grantedAtUtc", formatInstant(event.rewardedAtUtc()));

        // User-facing local values.
        payload.put("timezone", userZone.getId());
        payload.put("oldPremiumUntilLocal", formatLocalDateTimeOrFallback(
                event.oldPremiumUntil(),
                userZone,
                "No active Premium expiry"
        ));
        payload.put("newPremiumUntilLocal", formatLocalDateTime(event.newPremiumUntil(), userZone));
        payload.put("newExpiryDate", formatLocalDate(event.newPremiumUntil(), userZone));
        payload.put("grantedAtLocal", formatLocalDateTime(event.rewardedAtUtc(), userZone));
        payload.put("notificationDeepLink", buildInboxReferralDeepLink(event.claimId()));

        return objectMapper.writeValueAsString(payload);
    }

    private ZoneId resolveUserZone(Long userId) {
        return userProfileRepository.findByUserId(userId)
                .map(profile -> parseZoneOrDefault(profile.getTimezone()))
                .orElse(DEFAULT_ZONE);
    }

    private static ZoneId parseZoneOrDefault(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }

        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    private static String formatLocalDate(Instant value, ZoneId zone) {
        return value == null ? "" : LOCAL_DATE_FMT.withZone(zone).format(value);
    }

    private static String formatLocalDateTime(Instant value, ZoneId zone) {
        if (value == null) {
            return "";
        }

        return LOCAL_DATE_TIME_FMT.withZone(zone).format(value)
                + " (" + zone.getId() + ", " + formatOffset(value, zone) + ")";
    }

    private static String formatLocalDateTimeOrFallback(Instant value, ZoneId zone, String fallback) {
        return value == null ? fallback : formatLocalDateTime(value, zone);
    }

    private static String formatOffset(Instant value, ZoneId zone) {
        ZoneOffset offset = zone.getRules().getOffset(value);
        return offset.getId().equals("Z") ? "UTC" : "UTC" + offset.getId();
    }

    private static String formatInstant(Instant value) {
        return value == null ? "" : value.toString();
    }
}
