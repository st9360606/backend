package com.calai.backend.referral.service;

import com.calai.backend.referral.domain.ReferralClaimStatus;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Service
public class ReferralClaimService {
    private final ReferralCodeService referralCodeService;
    private final ReferralClaimRepository claimRepository;

    @Transactional
    public void claim(Long inviteeUserId, String rawPromoCode) {
        String promoCode = ReferralCodeService.normalizePromoCode(rawPromoCode);
        Long inviterUserId = referralCodeService.findInviterByPromoCode(promoCode);
        if (inviterUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ReferralRejectReason.INVALID_PROMO_CODE.name());
        }
        if (inviterUserId.equals(inviteeUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ReferralRejectReason.SELF_REFERRAL.name());
        }
        if (claimRepository.findByInviteeUserId(inviteeUserId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ReferralRejectReason.INVITEE_ALREADY_CLAIMED.name());
        }

        ReferralClaimEntity entity = new ReferralClaimEntity();
        entity.setInviterUserId(inviterUserId);
        entity.setInviteeUserId(inviteeUserId);
        entity.setPromoCode(promoCode);
        entity.setStatus(ReferralClaimStatus.PENDING_SUBSCRIPTION.name());
        entity.setRejectReason(ReferralRejectReason.NONE.name());
        claimRepository.save(entity);
    }
}
