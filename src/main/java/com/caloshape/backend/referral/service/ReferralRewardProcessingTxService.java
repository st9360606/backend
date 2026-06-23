package com.caloshape.backend.referral.service;

import com.caloshape.backend.referral.domain.ReferralClaimStatus;
import com.caloshape.backend.referral.entity.ReferralClaimEntity;
import com.caloshape.backend.referral.repo.ReferralClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Service
public class ReferralRewardProcessingTxService {

    private final ReferralClaimRepository claimRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimForProcessing(Long claimId, Instant now) {
        return claimRepository.claimForRewardProcessing(claimId, now) == 1;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ReferralClaimEntity loadClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalStateException("REFERRAL_CLAIM_NOT_FOUND: " + claimId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPendingAgain(Long claimId) {
        ReferralClaimEntity claim = loadClaimForUpdate(claimId);
        claim.setStatus(ReferralClaimStatus.PENDING_COOLDOWN.name());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long claimId, Instant rewardedAtUtc) {
        ReferralClaimEntity claim = loadClaimForUpdate(claimId);
        claim.setStatus(ReferralClaimStatus.SUCCESS.name());
        claim.setRewardedAtUtc(rewardedAtUtc);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRejected(Long claimId, String reason) {
        ReferralClaimEntity claim = loadClaimForUpdate(claimId);
        claim.setStatus(ReferralClaimStatus.REJECTED.name());
        claim.setRejectReason(reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(Long claimId, String reason) {
        ReferralClaimEntity claim = loadClaimForUpdate(claimId);
        claim.setStatus(ReferralClaimStatus.EXPIRED.name());
        claim.setRejectReason(reason);
    }

    private ReferralClaimEntity loadClaimForUpdate(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalStateException("REFERRAL_CLAIM_NOT_FOUND: " + claimId));
    }
}
