package com.calai.backend.foodlog.service;


import com.calai.backend.foodlog.quota.QuotaDayKey;
import com.calai.backend.foodlog.repo.UsageCounterRepository;
import com.calai.backend.foodlog.web.QuotaExceededException;
import com.calai.backend.foodlog.web.SubscriptionRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

@RequiredArgsConstructor
@Service
public class QuotaService {

    private final UsageCounterRepository repo;
    private final EntitlementService entitlement;

    @Value("${app.quota.daily.trial:30}")
    private int trialLimit;

    @Value("${app.quota.daily.monthly:200}")
    private int monthlyLimit;

    @Value("${app.quota.daily.yearly:400}")
    private int yearlyLimit;

    /**
     * ✅ 原子扣點：
     * - row 不存在：INSERT used_count=1（若超過 limit，當場擋）
     * - row 存在：UPDATE ... WHERE used_count < limit
     */
    @Transactional
    public LocalDate consumeAiOrThrow(Long userId, ZoneId userTz, Instant serverNowUtc) {
        EntitlementService.Tier tier = entitlement.resolveTier(userId, serverNowUtc);
        int limit = limitOf(tier);

        if (limit <= 0) {
            throw new SubscriptionRequiredException("SUBSCRIPTION_REQUIRED", "GO_SUBSCRIBE");
        }

        LocalDate dayKey = QuotaDayKey.todayLocalDate(serverNowUtc, userTz);

        int inserted = repo.insertFirstUse(userId, dayKey, serverNowUtc);
        if (inserted == 1) {
            // 第一次使用直接 used_count=1；若 limit=0 才會不合法（上面已擋）
            if (1 > limit) {
                int retryAfter = secondsUntilNextLocalDay(serverNowUtc, userTz);
                throw new QuotaExceededException("QUOTA_EXCEEDED", retryAfter, "WAIT_TOMORROW");
            }
            return dayKey;
        }

        int updated = repo.incrementIfBelowLimit(userId, dayKey, limit, serverNowUtc);
        if (updated == 1) return dayKey;

        int retryAfter = secondsUntilNextLocalDay(serverNowUtc, userTz);
        throw new QuotaExceededException("QUOTA_EXCEEDED", retryAfter, "WAIT_TOMORROW");
    }

    private int limitOf(EntitlementService.Tier tier) {
        return switch (tier) {
            case TRIAL -> trialLimit;
            case MONTHLY -> monthlyLimit;
            case YEARLY -> yearlyLimit;
            case NONE -> 0;
        };
    }

    private static int secondsUntilNextLocalDay(Instant nowUtc, ZoneId tz) {
        ZonedDateTime now = ZonedDateTime.ofInstant(nowUtc, tz);
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(tz);
        long sec = Duration.between(now, next).getSeconds();
        if (sec < 0) sec = 0;
        if (sec > Integer.MAX_VALUE) sec = Integer.MAX_VALUE;
        return (int) sec;
    }
}
