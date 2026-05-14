package com.calai.backend.referral.service;

import com.calai.backend.referral.dto.ReferralClaimItemDto;
import com.calai.backend.referral.dto.ReferralSummaryResponse;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import com.calai.backend.referral.domain.ReferralClaimStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ReferralQueryService {
    private final ReferralCodeService referralCodeService;
    private final ReferralClaimRepository claimRepository;

    public ReferralSummaryResponse getSummary(Long inviterUserId) {
        String promoCode = referralCodeService.getOrCreateCode(inviterUserId);
        long successCount = claimRepository.countByInviterUserIdAndStatus(inviterUserId, "SUCCESS");
        long pendingCount = claimRepository.countByInviterUserIdAndStatusIn(inviterUserId, pendingStatuses());
        long rejectedCount = claimRepository.countByInviterUserIdAndStatusIn(inviterUserId, rejectedStatuses());
        List<ReferralClaimItemDto> recentClaims = claimRepository.findTop20ByInviterUserIdOrderByCreatedAtUtcDesc(inviterUserId)
                .stream()
                .map(it -> new ReferralClaimItemDto(
                        it.getId(),
                        "A friend",
                        it.getStatus(),
                        resolveReferralDeadline(it),
                        it.getRewardedAtUtc(),
                        normalizeReasonForInviter(it.getRejectReason())
                ))
                .toList();
        return new ReferralSummaryResponse(promoCode, successCount, pendingCount, rejectedCount, recentClaims);
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

    private Instant resolveReferralDeadline(com.calai.backend.referral.entity.ReferralClaimEntity claim) {
        return claim.getCooldownUntilUtc() != null
                ? claim.getCooldownUntilUtc()
                : claim.getVerificationDeadlineUtc();
    }

    private String normalizeReasonForInviter(String reason) {
        if (reason == null || reason.isBlank() || "NONE".equals(reason)) return null;
        return switch (reason) {
            case "REFUNDED_OR_REVOKED", "CHARGEBACK" -> "Payment was refunded or revoked";
            case "ABUSE_RISK", "RISK_REJECTED" -> "This referral did not meet verification requirements";
            case "PURCHASE_PENDING" -> "The subscription payment was not completed";
            case "PURCHASE_NOT_VERIFIED" -> "The subscription payment could not be verified";
            case "TEST_PURCHASE" -> "Test purchases do not qualify for referral rewards";
            case "REWARD_GRANT_FAILED" -> "We could not apply the referral reward";
            case "VERIFICATION_WINDOW_EXPIRED" -> "The invited account did not subscribe in time";
            default -> "This referral did not qualify for a reward";
        };
    }
}
