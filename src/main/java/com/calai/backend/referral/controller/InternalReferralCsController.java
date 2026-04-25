package com.calai.backend.referral.controller;

import com.calai.backend.internal.InternalApiGuard;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralCaseSnapshotRepository;
import com.calai.backend.referral.repo.ReferralClaimRepository;
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

    @GetMapping("/inviter/{userId}")
    public Map<String, Object> getInviterReferralCase(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @PathVariable Long userId
    ) {
        internalApiGuard.requireValidToken(internalToken);

        var snapshot = snapshotRepository.findByInviterUserId(userId).orElse(null);
        var claims = claimRepository.findTop20ByInviterUserIdOrderByCreatedAtUtcDesc(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inviterUserId", userId);
        body.put("snapshot", snapshot);
        body.put("recentClaims", claims);
        return body;
    }

    @GetMapping("/claim/{claimId}")
    public ReferralClaimEntity getClaim(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @PathVariable Long claimId
    ) {
        internalApiGuard.requireValidToken(internalToken);

        return claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("REFERRAL_CLAIM_NOT_FOUND"));
    }
}
