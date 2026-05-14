package com.calai.backend.referral.service;

import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.entity.ReferralRiskSignalEntity;
import com.calai.backend.referral.repo.ReferralRiskSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class ReferralRiskService {

    public record RiskResult(
            int score,
            String decision,
            String flagsJson
    ) {
        public boolean denied() {
            return "DENY".equalsIgnoreCase(decision);
        }
    }

    private final ReferralRiskSignalRepository riskSignalRepository;

    /**
     * Dev/ops escape hatch for pre-claim risk rejection tests.
     * Production can keep this empty unless a real pre-claim risk engine is wired in.
     * Format: app.referral.claim-risk.reject-user-ids=1,2,3
     */
    @Value("${app.referral.claim-risk.reject-user-ids:}")
    private String claimRiskRejectUserIds;

    /**
     * Pre-claim guard used by onboarding bootstrap before the user has entered a
     * promo code. This keeps risk-rejected users from seeing the referral-code
     * page in the first place.
     */
    public boolean shouldRejectPreClaim(Long inviteeUserId) {
        if (inviteeUserId == null) {
            return true;
        }
        return isConfiguredClaimRiskRejected(inviteeUserId);
    }

    /**
     * Full claim guard used by POST /api/v1/referrals/claim after a promo code is
     * known. Keep this method separate from shouldRejectPreClaim() so a future
     * real risk engine can use inviter/code signals without changing bootstrap.
     */
    public boolean shouldRejectClaim(Long inviteeUserId, Long inviterUserId, String promoCode) {
        return shouldRejectPreClaim(inviteeUserId);
    }

    private boolean isConfiguredClaimRiskRejected(Long userId) {
        if (claimRiskRejectUserIds == null || claimRiskRejectUserIds.isBlank()) {
            return false;
        }

        String target = String.valueOf(userId);
        String[] parts = claimRiskRejectUserIds.split(",");
        for (String part : parts) {
            if (target.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    public RiskResult assessPaidSubscription(
            ReferralClaimEntity claim,
            String purchaseTokenHash,
            boolean pending,
            boolean testPurchase,
            Instant now
    ) {
        int score = 0;
        List<String> flags = new ArrayList<>();

        if (claim.getInviterUserId() != null
                && claim.getInviterUserId().equals(claim.getInviteeUserId())) {
            score += 100;
            flags.add("SELF_REFERRAL");
        }

        if (purchaseTokenHash == null || purchaseTokenHash.isBlank()) {
            score += 100;
            flags.add("MISSING_PURCHASE_TOKEN_HASH");
        }

        if (pending) {
            score += 30;
            flags.add("PURCHASE_PENDING");
        }

        if (testPurchase) {
            score += 50;
            flags.add("TEST_PURCHASE");
        }

        String decision = score >= 80 ? "DENY" : "ALLOW";
        String flagsJson = toJson(flags);

        ReferralRiskSignalEntity signal = new ReferralRiskSignalEntity();
        signal.setClaimId(claim.getId());
        signal.setRiskScore(score);
        signal.setDecision(decision);
        signal.setRiskFlagsJson(flagsJson);
        signal.setCreatedAtUtc(now);
        riskSignalRepository.save(signal);

        claim.setRiskScore(score);
        claim.setRiskDecision(decision);

        return new RiskResult(score, decision, flagsJson);
    }

    private static String toJson(List<String> flags) {
        if (flags == null || flags.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < flags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(flags.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
