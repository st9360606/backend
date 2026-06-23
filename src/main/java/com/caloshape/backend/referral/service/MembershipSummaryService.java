package com.caloshape.backend.referral.service;

import com.caloshape.backend.entitlement.entity.UserEntitlementEntity;
import com.caloshape.backend.entitlement.repo.UserEntitlementRepository;
import com.caloshape.backend.referral.domain.PremiumStatus;
import com.caloshape.backend.referral.dto.MembershipSummaryResponse;
import com.caloshape.backend.referral.dto.RewardHistoryItemDto;
import com.caloshape.backend.referral.entity.MembershipRewardLedgerEntity;
import com.caloshape.backend.referral.repo.MembershipRewardLedgerRepository;
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

        String premiumStatus = resolvePremiumStatus(active, now);

        Instant activeUntil = active == null ? null : active.getValidToUtc();

        /*
         * Important:
         *
         * activeUntil only represents the currently active entitlement segment.
         *
         * Example:
         *   2026-05-13 -> 2026-06-12  currently active
         *   2026-06-12 -> 2026-07-12  future contiguous reward
         *
         * If we only return activeUntil, the App shows 2026-06-12.
         * For PREMIUM users, we should merge contiguous future premium
         * entitlements and return the final expiry, 2026-07-12.
         */
        Instant currentPremiumUntil =
                PremiumStatus.PREMIUM.name().equals(premiumStatus) && active != null
                        ? resolveContiguousPremiumUntil(userId, active, now)
                        : activeUntil;

        Instant trialEndsAt = PremiumStatus.TRIAL.name().equals(premiumStatus)
                ? activeUntil
                : null;

        Integer trialDaysLeft = PremiumStatus.TRIAL.name().equals(premiumStatus)
                ? calcDaysLeft(now, activeUntil)
                : null;

        boolean trialEligible = !entitlementRepository.existsAnyTrialHistory(userId);

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
                trialEligible,
                paymentIssue,
                latest == null ? null : latest.getSourceType(),
                latest == null ? null : latest.getRewardChannel(),
                latest == null ? null : latest.getGrantStatus(),
                latest == null ? null : latest.getGoogleDeferStatus(),
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

    private Instant resolveContiguousPremiumUntil(
            Long userId,
            UserEntitlementEntity active,
            Instant now
    ) {
        Instant currentUntil = active.getValidToUtc();

        if (currentUntil == null || !currentUntil.isAfter(now)) {
            return null;
        }

        List<UserEntitlementEntity> candidates =
                entitlementRepository.findUsableActiveAndFutureEntitlements(userId, now);

        for (UserEntitlementEntity candidate : candidates) {
            if (isTrialEntitlement(candidate)) {
                continue;
            }

            Instant from = candidate.getValidFromUtc();
            Instant to = candidate.getValidToUtc();

            if (from == null || to == null || !to.isAfter(now)) {
                continue;
            }

            /*
             * Only merge overlapping or exactly contiguous entitlements.
             *
             * Example:
             *   currentUntil = 2026-06-12
             *   from         = 2026-06-12
             *   to           = 2026-07-12
             *
             * This should merge.
             */
            boolean contiguousOrOverlapping = !from.isAfter(currentUntil);

            if (contiguousOrOverlapping && to.isAfter(currentUntil)) {
                currentUntil = to;
            }
        }

        return currentUntil;
    }

    private boolean isTrialEntitlement(UserEntitlementEntity entitlement) {
        return "TRIAL".equalsIgnoreCase(entitlement.getEntitlementType());
    }

    private String resolvePremiumStatus(UserEntitlementEntity active, Instant now) {
        if (active == null) {
            return PremiumStatus.FREE.name();
        }

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
        if (end == null || !end.isAfter(now)) {
            return 0;
        }

        long seconds = Duration.between(now, end).getSeconds();

        return (int) Math.max(1, Math.ceil(seconds / 86_400.0));
    }

    private RewardHistoryItemDto map(MembershipRewardLedgerEntity entity) {
        return new RewardHistoryItemDto(
                entity.getId(),
                entity.getSourceType(),
                entity.getSourceRefId(),
                entity.getGrantStatus(),
                entity.getRewardChannel(),
                entity.getGoogleDeferStatus(),
                entity.getErrorCode(),
                entity.getDaysAdded(),
                entity.getOldPremiumUntil(),
                entity.getNewPremiumUntil(),
                entity.getGrantedAtUtc()
        );
    }
}