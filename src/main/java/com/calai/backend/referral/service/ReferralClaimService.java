package com.calai.backend.referral.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.domain.PremiumStatus;
import com.calai.backend.referral.domain.ReferralClaimStatus;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.dto.ClaimReferralResponse;
import com.calai.backend.referral.dto.MembershipSummaryResponse;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Service
public class ReferralClaimService {
    private final ReferralCodeService referralCodeService;
    private final ReferralClaimRepository claimRepository;
    private final UserEntitlementRepository entitlementRepository;
    private final MembershipSummaryService membershipSummaryService;
    private final ReferralRiskService referralRiskService;

    @Value("${app.referral.enabled:true}")
    private boolean referralEnabled;

    @Transactional
    public ClaimReferralResponse claim(Long inviteeUserId, String rawPromoCode) {
        if (!referralEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ReferralRejectReason.REFERRAL_DISABLED.name()
            );
        }

        String promoCode = ReferralCodeService.normalizePromoCode(rawPromoCode);
        Long inviterUserId = referralCodeService.findInviterByPromoCode(promoCode);
        if (inviterUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ReferralRejectReason.INVALID_PROMO_CODE.name());
        }
        if (inviterUserId.equals(inviteeUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ReferralRejectReason.SELF_REFERRAL.name());
        }

        ReferralClaimEntity existingClaim = claimRepository.findByInviteeUserId(inviteeUserId).orElse(null);
        if (existingClaim != null) {
            if (inviterUserId.equals(existingClaim.getInviterUserId())
                    && promoCode.equalsIgnoreCase(existingClaim.getPromoCode())) {
                return new ClaimReferralResponse(true, true, existingClaim.getStatus());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, ReferralRejectReason.INVITEE_ALREADY_CLAIMED.name());
        }

        assertReferralClaimEligibility(inviteeUserId, inviterUserId, promoCode);

        /*
         * Referral v1.3：invitee 必須完成「首次」有效付費訂閱。
         * 若使用者歷史上已經有 Google Play paid subscription，不能再 claim referral。
         */
        if (entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(inviteeUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ReferralRejectReason.INVITEE_ALREADY_SUBSCRIBED.name());
        }

        ReferralClaimEntity entity = new ReferralClaimEntity();
        entity.setInviterUserId(inviterUserId);
        entity.setInviteeUserId(inviteeUserId);
        entity.setPromoCode(promoCode);
        entity.setStatus(ReferralClaimStatus.PENDING_SUBSCRIPTION.name());
        entity.setRejectReason(ReferralRejectReason.NONE.name());
        claimRepository.save(entity);
        return new ClaimReferralResponse(true, false, entity.getStatus());
    }

    /**
     * Server-side guard for direct API calls.
     *
     * The App already uses /api/v1/onboarding/bootstrap to decide whether the
     * referral-code page should be shown, but a production backend must never
     * rely on the client to enforce eligibility. This method mirrors the
     * bootstrap business rules before a claim can be created.
     */
    private void assertReferralClaimEligibility(
            Long inviteeUserId,
            Long inviterUserId,
            String promoCode
    ) {
        MembershipSummaryResponse membership = membershipSummaryService.getMembershipSummary(inviteeUserId);
        String premiumStatus = membership.premiumStatus() == null
                ? PremiumStatus.FREE.name()
                : membership.premiumStatus();

        if (PremiumStatus.PREMIUM.name().equals(premiumStatus)) {
            String reason = Boolean.TRUE.equals(membership.paymentIssue())
                    ? ReferralRejectReason.PAYMENT_ISSUE.name()
                    : ReferralRejectReason.PREMIUM_ACTIVE.name();
            throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
        }

        if (PremiumStatus.TRIAL.name().equals(premiumStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ReferralRejectReason.TRIAL_ACTIVE.name());
        }

        boolean hasPaidHistory = entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(inviteeUserId);
        if (hasPaidHistory) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ReferralRejectReason.INVITEE_ALREADY_SUBSCRIBED.name());
        }

        if (referralRiskService.shouldRejectClaim(inviteeUserId, inviterUserId, promoCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ReferralRejectReason.RISK_REJECTED.name());
        }
    }
}
