package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralOutcomeType;
import com.calai.backend.referral.entity.EmailOutboxEntity;
import com.calai.backend.referral.entity.ReferralCaseSnapshotEntity;
import com.calai.backend.referral.entity.UserNotificationEntity;
import com.calai.backend.referral.repo.EmailOutboxRepository;
import com.calai.backend.referral.repo.ReferralCaseSnapshotRepository;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import com.calai.backend.referral.repo.UserNotificationRepository;
import com.calai.backend.users.user.repo.UserRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@RequiredArgsConstructor
@Component
public class ReferralOutcomeConsumers {
    private final UserNotificationRepository notificationRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final ReferralCaseSnapshotRepository caseSnapshotRepository;
    private final ReferralClaimRepository claimRepository;
    private final UserRepo userRepo;
    private final MembershipSummaryService membershipSummaryService;

    @TransactionalEventListener
    public void onOutcome(ReferralOutcomeEvent event) {
        createNotification(event);
        createEmailOutbox(event);
        refreshSnapshot(event.inviterUserId());
    }

    private void createNotification(ReferralOutcomeEvent event) {
        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setUserId(event.inviterUserId());
        entity.setType(event.outcomeType().name());
        entity.setSourceType("REFERRAL_CLAIM");
        entity.setSourceRefId(event.claimId());
        if (event.outcomeType() == ReferralOutcomeType.GRANTED) {
            entity.setTitle("Referral reward granted");
            entity.setMessage("Your Premium has been extended by 30 days. New expiry date: " + event.newPremiumUntil());
            entity.setDeepLink("bitecal://premium-rewards");
        } else {
            entity.setTitle("Referral reward not granted");
            entity.setMessage("Your referral did not qualify for a reward. Reason: " + sanitizeReason(event.rejectReason()));
            entity.setDeepLink("bitecal://referrals");
        }
        notificationRepository.save(entity);
    }

    private void createEmailOutbox(ReferralOutcomeEvent event) {
        String email = userRepo.findById(event.inviterUserId()).map(u -> u.getEmail()).orElse(null);
        if (email == null || email.isBlank()) return;
        String dedupeKey = "referral:" + event.outcomeType().name().toLowerCase() + ":" + event.claimId();
        if (emailOutboxRepository.existsByDedupeKey(dedupeKey)) return;

        EmailOutboxEntity outbox = new EmailOutboxEntity();
        outbox.setUserId(event.inviterUserId());
        outbox.setToEmail(email);
        outbox.setTemplateType(event.outcomeType() == ReferralOutcomeType.GRANTED ? "REFERRAL_GRANTED" : "REFERRAL_REJECTED");
        outbox.setDedupeKey(dedupeKey);
        outbox.setTemplatePayloadJson(buildPayload(event));
        emailOutboxRepository.save(outbox);
    }

    private void refreshSnapshot(Long inviterUserId) {
        long successCount = claimRepository.countByInviterUserIdAndStatus(inviterUserId, "SUCCESS");
        long pendingCount = claimRepository.countByInviterUserIdAndStatus(inviterUserId, "PENDING_VERIFICATION");
        long rejectedCount = claimRepository.countByInviterUserIdAndStatus(inviterUserId, "REJECTED");
        long totalInvited = successCount + pendingCount + rejectedCount + claimRepository.countByInviterUserIdAndStatus(inviterUserId, "PENDING_SUBSCRIPTION");
        int totalRewardedDays = membershipSummaryService.getRewardHistory(inviterUserId)
                .stream()
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

    private String buildPayload(ReferralOutcomeEvent event) {
        if (event.outcomeType() == ReferralOutcomeType.GRANTED) {
            return "{\"claimId\":" + event.claimId() + ",\"daysAdded\":30,\"oldPremiumUntil\":\"" + event.oldPremiumUntil() + "\",\"newPremiumUntil\":\"" + event.newPremiumUntil() + "\",\"grantedAtUtc\":\"" + event.rewardedAtUtc() + "\"}";
        }
        return "{\"claimId\":" + event.claimId() + ",\"reason\":\"" + sanitizeReason(event.rejectReason()) + "\"}";
    }

    private String sanitizeReason(String raw) {
        if (raw == null || raw.isBlank()) return "This referral did not qualify for a reward";
        return switch (raw) {
            case "REFUNDED_OR_REVOKED", "CHARGEBACK" -> "Payment was refunded or revoked";
            case "ABUSE_RISK" -> "This referral did not meet verification requirements";
            default -> "This referral did not qualify for a reward";
        };
    }
}
