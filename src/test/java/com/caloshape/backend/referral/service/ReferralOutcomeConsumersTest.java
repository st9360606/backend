package com.caloshape.backend.referral.service;

import com.caloshape.backend.referral.domain.ReferralOutcomeType;
import com.caloshape.backend.referral.dto.MembershipSummaryResponse;
import com.caloshape.backend.referral.entity.EmailOutboxEntity;
import com.caloshape.backend.referral.entity.UserNotificationEntity;
import com.caloshape.backend.referral.repo.EmailOutboxRepository;
import com.caloshape.backend.referral.repo.ReferralCaseSnapshotRepository;
import com.caloshape.backend.referral.repo.ReferralClaimRepository;
import com.caloshape.backend.referral.repo.UserNotificationRepository;
import com.caloshape.backend.users.profile.entity.UserProfile;
import com.caloshape.backend.users.profile.repo.UserProfileRepository;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralOutcomeConsumersTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private UserNotificationRepository notificationRepository;

    @Mock
    private EmailOutboxRepository emailOutboxRepository;

    @Mock
    private ReferralCaseSnapshotRepository caseSnapshotRepository;

    @Mock
    private ReferralClaimRepository claimRepository;

    @Mock
    private UserRepo userRepo;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private MembershipSummaryService membershipSummaryService;

    private ReferralOutcomeConsumers consumers;

    @BeforeEach
    void setUp() {
        consumers = new ReferralOutcomeConsumers(
                notificationRepository,
                emailOutboxRepository,
                caseSnapshotRepository,
                claimRepository,
                userRepo,
                userProfileRepository,
                membershipSummaryService,
                objectMapper
        );

        when(membershipSummaryService.getRewardHistory(100L)).thenReturn(List.of());
        when(membershipSummaryService.getMembershipSummary(100L)).thenReturn(summary());
        when(notificationRepository.findByUserIdAndSourceTypeAndSourceRefId(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void onOutcome_shouldCreateNotificationAndEmailOutboxForGrantedEvent() throws Exception {
        User inviter = user("inviter@example.com");

        when(userRepo.findById(100L)).thenReturn(Optional.of(inviter));
        when(userProfileRepository.findByUserId(100L)).thenReturn(Optional.of(profile("Asia/Taipei")));

        consumers.onOutcome(grantedEvent());

        ArgumentCaptor<UserNotificationEntity> notificationCaptor =
                ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(notificationRepository).save(notificationCaptor.capture());

        UserNotificationEntity notification = notificationCaptor.getValue();
        assertThat(notification.getUserId()).isEqualTo(100L);
        assertThat(notification.getType()).isEqualTo("GRANTED");
        assertThat(notification.getTitle()).isEqualTo("🎉 30 free Premium days unlocked");
        assertThat(notification.getMessage()).isEqualTo("Your CaloShape Premium has been extended by 30 days.\nNew expiry: 2026-05-31");
        assertThat(notification.getDeepLink()).isEqualTo("caloshape://settings/inbox/referral/10");

        ArgumentCaptor<EmailOutboxEntity> emailCaptor = ArgumentCaptor.forClass(EmailOutboxEntity.class);
        verify(emailOutboxRepository).save(emailCaptor.capture());

        EmailOutboxEntity outbox = emailCaptor.getValue();
        assertThat(outbox.getUserId()).isEqualTo(100L);
        assertThat(outbox.getToEmail()).isEqualTo("inviter@example.com");
        assertThat(outbox.getTemplateType()).isEqualTo("REFERRAL_GRANTED");
        assertThat(outbox.getDedupeKey()).isEqualTo("referral:granted:10");
        assertThat(outbox.getStatus()).isEqualTo("PENDING");

        JsonNode payload = objectMapper.readTree(outbox.getTemplatePayloadJson());
        assertThat(payload.path("claimId").asLong()).isEqualTo(10L);
        assertThat(payload.path("daysAdded").asInt()).isEqualTo(30);

        assertThat(payload.path("oldPremiumUntil").asText()).isEqualTo("2026-05-01T00:00:00Z");
        assertThat(payload.path("newPremiumUntil").asText()).isEqualTo("2026-05-31T00:00:00Z");
        assertThat(payload.path("grantedAtUtc").asText()).isEqualTo("2026-05-10T00:00:00Z");

        assertThat(payload.path("timezone").asText()).isEqualTo("Asia/Taipei");
        assertThat(payload.path("oldPremiumUntilLocal").asText()).isEqualTo("2026-05-01 08:00 (Asia/Taipei, UTC+08:00)");
        assertThat(payload.path("newPremiumUntilLocal").asText()).isEqualTo("2026-05-31 08:00 (Asia/Taipei, UTC+08:00)");
        assertThat(payload.path("newExpiryDate").asText()).isEqualTo("2026-05-31");
        assertThat(payload.path("grantedAtLocal").asText()).isEqualTo("2026-05-10 08:00 (Asia/Taipei, UTC+08:00)");
    }

    @Test
    void onOutcome_shouldFallbackToUtcWhenUserTimezoneIsMissing() throws Exception {
        User inviter = user("inviter@example.com");

        when(userRepo.findById(100L)).thenReturn(Optional.of(inviter));
        when(userProfileRepository.findByUserId(100L)).thenReturn(Optional.empty());

        consumers.onOutcome(grantedEvent());

        ArgumentCaptor<EmailOutboxEntity> emailCaptor = ArgumentCaptor.forClass(EmailOutboxEntity.class);
        verify(emailOutboxRepository).save(emailCaptor.capture());

        JsonNode payload = objectMapper.readTree(emailCaptor.getValue().getTemplatePayloadJson());

        assertThat(payload.path("timezone").asText()).isEqualTo("Z");
        assertThat(payload.path("oldPremiumUntilLocal").asText()).isEqualTo("2026-05-01 00:00 (Z, UTC)");
        assertThat(payload.path("newPremiumUntilLocal").asText()).isEqualTo("2026-05-31 00:00 (Z, UTC)");
        assertThat(payload.path("grantedAtLocal").asText()).isEqualTo("2026-05-10 00:00 (Z, UTC)");
    }

    @Test
    void onOutcome_shouldNotCreateEmailOutboxForRejectedEvent() {
        consumers.onOutcome(new ReferralOutcomeEvent(
                10L,
                100L,
                ReferralOutcomeType.REJECTED,
                "PURCHASE_PENDING",
                null,
                null,
                null
        ));

        verify(notificationRepository).save(any(UserNotificationEntity.class));
        verify(emailOutboxRepository, never()).save(any());
        verify(userRepo, never()).findById(100L);
    }

    @Test
    void onOutcome_shouldSkipEmailOutboxWhenInviterEmailIsNull() {
        when(userRepo.findById(100L)).thenReturn(Optional.of(user(null)));

        consumers.onOutcome(grantedEvent());

        verify(notificationRepository).save(any(UserNotificationEntity.class));
        verify(emailOutboxRepository, never()).save(any());
    }

    @Test
    void onOutcome_shouldSkipEmailOutboxWhenInviterEmailIsBlank() {
        when(userRepo.findById(100L)).thenReturn(Optional.of(user("   ")));

        consumers.onOutcome(grantedEvent());

        verify(notificationRepository).save(any(UserNotificationEntity.class));
        verify(emailOutboxRepository, never()).save(any());
    }

    @Test
    void onOutcome_shouldNotCreateDuplicateEmailOutboxWhenDedupeKeyExists() {
        when(userRepo.findById(100L)).thenReturn(Optional.of(user("inviter@example.com")));
        when(emailOutboxRepository.existsByDedupeKey("referral:granted:10")).thenReturn(true);

        consumers.onOutcome(grantedEvent());

        verify(notificationRepository).save(any(UserNotificationEntity.class));
        verify(emailOutboxRepository, never()).save(any());
    }

    @Test
    void onOutcome_shouldRewriteRejectedNotificationWhenManualCompensationGrantsReward() {
        UserNotificationEntity rejected = new UserNotificationEntity();
        rejected.setUserId(100L);
        rejected.setType("REJECTED");
        rejected.setTitle("Referral reward not granted");
        rejected.setMessage("This referral did not qualify.");
        rejected.setSourceType("REFERRAL_CLAIM");
        rejected.setSourceRefId(10L);
        rejected.setRead(true);

        when(notificationRepository.findByUserIdAndSourceTypeAndSourceRefId(
                100L,
                "REFERRAL_CLAIM",
                10L
        )).thenReturn(Optional.of(rejected));
        when(userProfileRepository.findByUserId(100L)).thenReturn(Optional.of(profile("Asia/Taipei")));
        when(userRepo.findById(100L)).thenReturn(Optional.of(user("inviter@example.com")));

        consumers.onOutcome(grantedEvent());

        assertThat(rejected.getType()).isEqualTo("GRANTED");
        assertThat(rejected.getTitle()).contains("30 free Premium days unlocked");
        assertThat(rejected.getMessage()).isEqualTo("Your CaloShape Premium has been extended by 30 days.\nNew expiry: 2026-05-31");
        assertThat(rejected.isRead()).isFalse();
        verify(notificationRepository).save(rejected);
    }

    private ReferralOutcomeEvent grantedEvent() {
        return new ReferralOutcomeEvent(
                10L,
                100L,
                ReferralOutcomeType.GRANTED,
                null,
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-31T00:00:00Z"),
                Instant.parse("2026-05-10T00:00:00Z")
        );
    }

    private User user(String email) {
        User user = new User();
        user.setId(100L);
        user.setEmail(email);
        return user;
    }

    private UserProfile profile(String timezone) {
        UserProfile profile = new UserProfile();
        profile.setUserId(100L);
        profile.setTimezone(timezone);
        return profile;
    }

    private MembershipSummaryResponse summary() {
        return new MembershipSummaryResponse(
                "PREMIUM",
                Instant.parse("2026-05-31T00:00:00Z"),
                null,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
