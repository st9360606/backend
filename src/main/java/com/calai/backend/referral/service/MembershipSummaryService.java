package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.domain.PremiumStatus;
import com.calai.backend.referral.dto.MembershipSummaryResponse;
import com.calai.backend.referral.dto.RewardHistoryItemDto;
import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Service
public class MembershipSummaryService {

    private final UserEntitlementRepository entitlementRepository;
    private final MembershipRewardLedgerRepository rewardLedgerRepository;

    public MembershipSummaryResponse getMembershipSummary(Long userId) {
        Instant now = Instant.now();

        UserEntitlementEntity active = entitlementRepository
                .findActiveBestFirst(userId, now, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);

        Instant currentPremiumUntil = active == null ? null : active.getValidToUtc();
        String premiumStatus = resolvePremiumStatus(active, now);
        Instant trialEndsAt = PremiumStatus.TRIAL.name().equals(premiumStatus)
                ? currentPremiumUntil
                : null;
        Integer trialDaysLeft = PremiumStatus.TRIAL.name().equals(premiumStatus)
                ? calcDaysLeft(now, currentPremiumUntil)
                : null;

        MembershipRewardLedgerEntity latest = rewardLedgerRepository
                .findTop1ByUserIdOrderByGrantedAtUtcDesc(userId)
                .orElse(null);

        boolean paymentIssue =
                PremiumStatus.PREMIUM.name().equals(premiumStatus)
                        && active != null
                        && "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(active.getSubscriptionState());

        return new MembershipSummaryResponse(
                premiumStatus,
                currentPremiumUntil,
                trialEndsAt,
                trialDaysLeft,
                paymentIssue,
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

    private String resolvePremiumStatus(UserEntitlementEntity active, Instant now) {
        if (active == null) return PremiumStatus.FREE.name();

        Instant validTo = active.getValidToUtc();
        if (validTo == null || !validTo.isAfter(now)) {
            return PremiumStatus.FREE.name();
        }

        String type = active.getEntitlementType();
        if ("TRIAL".equalsIgnoreCase(type)) {
            return PremiumStatus.TRIAL.name();
        }

        return PremiumStatus.PREMIUM.name();
    }

    private int calcDaysLeft(Instant now, Instant end) {
        if (end == null || !end.isAfter(now)) return 0;
        long seconds = Duration.between(now, end).getSeconds();
        return (int) Math.max(1, Math.ceil(seconds / 86_400.0));
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
