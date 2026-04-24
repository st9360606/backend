package com.calai.backend.referral.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.domain.PremiumStatus;
import com.calai.backend.referral.dto.MembershipSummaryResponse;
import com.calai.backend.referral.dto.RewardHistoryItemDto;
import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Service
public class MembershipSummaryService {
    private final UserEntitlementRepository entitlementRepository;
    private final MembershipRewardLedgerRepository rewardLedgerRepository;

    public MembershipSummaryResponse getMembershipSummary(Long userId) {
        Instant now = Instant.now();
        Instant currentPremiumUntil = entitlementRepository.findActive(userId, now, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(e -> e.getValidToUtc())
                .orElse(null);

        MembershipRewardLedgerEntity latest = rewardLedgerRepository.findTop1ByUserIdOrderByGrantedAtUtcDesc(userId).orElse(null);
        return new MembershipSummaryResponse(
                currentPremiumUntil != null && currentPremiumUntil.isAfter(now) ? PremiumStatus.PREMIUM.name() : PremiumStatus.FREE.name(),
                currentPremiumUntil,
                latest == null ? null : latest.getSourceType(),
                latest == null ? null : latest.getOldPremiumUntil(),
                latest == null ? null : latest.getNewPremiumUntil(),
                latest == null ? null : latest.getGrantedAtUtc()
        );
    }

    public List<RewardHistoryItemDto> getRewardHistory(Long userId) {
        return rewardLedgerRepository.findTop50ByUserIdOrderByGrantedAtUtcDesc(userId)
                .stream()
                .map(this::map)
                .toList();
    }

    private RewardHistoryItemDto map(MembershipRewardLedgerEntity entity) {
        return new RewardHistoryItemDto(
                entity.getId(),
                entity.getSourceType(),
                entity.getSourceRefId(),
                entity.getDaysAdded(),
                entity.getOldPremiumUntil(),
                entity.getNewPremiumUntil(),
                entity.getGrantedAtUtc()
        );
    }
}
