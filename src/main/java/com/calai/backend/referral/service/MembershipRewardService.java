package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Service
public class MembershipRewardService {
    public record RewardGrantResult(Instant oldPremiumUntil, Instant newPremiumUntil, Instant grantedAtUtc) {}

    private static final int DAYS_ADDED = 30;
    private final UserEntitlementRepository entitlementRepository;
    private final MembershipRewardLedgerRepository rewardLedgerRepository;

    @Transactional
    public RewardGrantResult grantReferralReward(Long inviterUserId, Long claimId) {
        if (rewardLedgerRepository.existsBySourceTypeAndSourceRefId("REFERRAL_SUCCESS", claimId)) {
            MembershipRewardLedgerEntity existing = rewardLedgerRepository.findTop50ByUserIdOrderByGrantedAtUtcDesc(inviterUserId)
                    .stream()
                    .filter(it -> "REFERRAL_SUCCESS".equals(it.getSourceType()) && claimId.equals(it.getSourceRefId()))
                    .findFirst()
                    .orElseThrow();
            return new RewardGrantResult(existing.getOldPremiumUntil(), existing.getNewPremiumUntil(), existing.getGrantedAtUtc());
        }

        Instant now = Instant.now();
        List<UserEntitlementEntity> active = entitlementRepository.findActive(inviterUserId, now, PageRequest.of(0, 1));
        UserEntitlementEntity entitlement = active.isEmpty() ? null : active.get(0);
        Instant oldPremiumUntil = entitlement == null ? null : entitlement.getValidToUtc();
        Instant base = oldPremiumUntil != null && oldPremiumUntil.isAfter(now) ? oldPremiumUntil : now;
        Instant newPremiumUntil = base.plusSeconds(DAYS_ADDED * 24L * 3600L);

        if (entitlement != null) {
            entitlement.setValidToUtc(newPremiumUntil);
            entitlement.setLastVerifiedAtUtc(now);
            entitlementRepository.save(entitlement);
        } else {
            UserEntitlementEntity created = new UserEntitlementEntity();
            created.setUserId(inviterUserId);
            created.setEntitlementType("MONTHLY");
            created.setStatus("ACTIVE");
            created.setValidFromUtc(now);
            created.setValidToUtc(newPremiumUntil);
            created.setLastVerifiedAtUtc(now);
            entitlementRepository.save(created);
        }

        MembershipRewardLedgerEntity ledger = new MembershipRewardLedgerEntity();
        ledger.setUserId(inviterUserId);
        ledger.setSourceType("REFERRAL_SUCCESS");
        ledger.setSourceRefId(claimId);
        ledger.setGrantStatus("GRANTED");
        ledger.setDaysAdded(DAYS_ADDED);
        ledger.setOldPremiumUntil(oldPremiumUntil);
        ledger.setNewPremiumUntil(newPremiumUntil);
        ledger.setGrantedAtUtc(now);
        rewardLedgerRepository.save(ledger);

        return new RewardGrantResult(oldPremiumUntil, newPremiumUntil, now);
    }
}
