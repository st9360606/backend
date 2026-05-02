package com.calai.backend.referral.controller;

import com.calai.backend.internal.InternalApiGuard;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.EmailOutboxRepository;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import com.calai.backend.referral.repo.ReferralCaseSnapshotRepository;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import com.calai.backend.referral.repo.ReferralRiskSignalRepository;
import com.calai.backend.referral.repo.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/cs/referrals")
public class InternalReferralCsController {

    private final InternalApiGuard internalApiGuard;
    private final ReferralCaseSnapshotRepository snapshotRepository;
    private final ReferralClaimRepository claimRepository;
    private final MembershipRewardLedgerRepository rewardLedgerRepository;
    private final ReferralRiskSignalRepository riskSignalRepository;
    private final UserNotificationRepository notificationRepository;
    private final EmailOutboxRepository emailOutboxRepository;

    @GetMapping("/inviter/{userId}")
    public Map<String, Object> getInviterReferralCase(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @PathVariable Long userId
    ) {
        internalApiGuard.requireValidToken(internalToken);

        var snapshot = snapshotRepository.findByInviterUserId(userId).orElse(null);
        var claims = claimRepository.findTop20ByInviterUserIdOrderByCreatedAtUtcDesc(userId);
        var rewards = rewardLedgerRepository.findTop50ByUserIdOrderByGrantedAtUtcDesc(userId);
        var notifications = notificationRepository.findTop20ByUserIdOrderByCreatedAtUtcDesc(userId);
        var emails = emailOutboxRepository.findTop20ByUserIdOrderByCreatedAtUtcDesc(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inviterUserId", userId);
        body.put("snapshot", snapshot);
        body.put("recentClaims", claims);
        body.put("rewardLedger", rewards);
        body.put("notifications", notifications);
        body.put("emailOutbox", emails);
        return body;
    }

    @GetMapping("/claim/{claimId}")
    public Map<String, Object> getClaimTrace(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @PathVariable Long claimId
    ) {
        internalApiGuard.requireValidToken(internalToken);

        ReferralClaimEntity claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("REFERRAL_CLAIM_NOT_FOUND"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claim", claim);
        body.put("rewardLedger", rewardLedgerRepository.findBySourceTypeAndSourceRefIdOrderByAttemptNoAsc("REFERRAL_SUCCESS", claimId));
        body.put("riskSignals", riskSignalRepository.findTop20ByClaimIdOrderByCreatedAtUtcDesc(claimId));
        body.put("notifications", notificationRepository.findTop20ByUserIdAndSourceTypeAndSourceRefIdOrderByCreatedAtUtcDesc(
                claim.getInviterUserId(),
                "REFERRAL_CLAIM",
                claimId
        ));
        body.put("emailOutbox", emailOutboxRepository.findTop20ByUserIdAndDedupeKeyContainingOrderByCreatedAtUtcDesc(
                claim.getInviterUserId(),
                ":" + claimId
        ));
        return body;
    }
}
