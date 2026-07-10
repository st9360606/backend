package com.caloshape.backend.referral.service;

import com.caloshape.backend.referral.domain.ReferralClaimStatus;
import com.caloshape.backend.referral.domain.ReferralOutcomeType;
import com.caloshape.backend.referral.entity.ReferralClaimEntity;
import com.caloshape.backend.referral.repo.ReferralClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Service
public class ReferralManualCompensationService {

    public record ManualCompensationResult(
            Long claimId,
            Long inviterUserId,
            Instant oldPremiumUntil,
            Instant newPremiumUntil,
            Instant grantedAtUtc
    ) {}

    private final ReferralClaimRepository claimRepository;
    private final MembershipRewardService membershipRewardService;
    private final ReferralOutcomePublisher outcomePublisher;

    @Transactional
    public ManualCompensationResult compensateAfterGoogleDeferFinalFailure(Long claimId) {
        ReferralClaimEntity claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("REFERRAL_CLAIM_NOT_FOUND"));

        MembershipRewardService.RewardGrantResult grant =
                membershipRewardService.grantManualBackendCompensationAfterGoogleDeferFinalFailure(
                        claim.getInviterUserId(),
                        claim.getId()
                );

        claim.setStatus(ReferralClaimStatus.SUCCESS.name());
        claim.setRewardedAtUtc(grant.grantedAtUtc());
        claim.setRejectReason("NONE");

        outcomePublisher.publish(new ReferralOutcomeEvent(
                claim.getId(),
                claim.getInviterUserId(),
                ReferralOutcomeType.GRANTED,
                null,
                grant.oldPremiumUntil(),
                grant.newPremiumUntil(),
                grant.grantedAtUtc()
        ));

        return new ManualCompensationResult(
                claim.getId(),
                claim.getInviterUserId(),
                grant.oldPremiumUntil(),
                grant.newPremiumUntil(),
                grant.grantedAtUtc()
        );
    }
}
