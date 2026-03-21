package com.calai.backend.foodlog.quota.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.web.error.CooldownActiveException;
import com.calai.backend.foodlog.web.error.SubscriptionRequiredException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class QuotaService {

    private final UserAiQuotaStateRepository repo;
    private final EntitlementService entitlementService;

    @Value("${app.ai.quota.cooldown.step-minutes:10}")
    private int cooldownStepMinutes;

    @Value("${app.ai.quota.cooldown.max-minutes:30}")
    private int maxCooldownMinutes;

    @Value("${app.ai.quota.trial.premium-limit:15}")
    private int trialPremiumLimit;

    @Value("${app.ai.quota.trial.total-limit:30}")
    private int trialTotalLimit;

    @Value("${app.ai.quota.paid.premium-limit:300}")
    private int paidPremiumLimit;

    @Value("${app.ai.quota.paid.total-limit:800}")
    private int paidTotalLimit;

    private static final DateTimeFormatter DAY_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    public record Decision(ModelTier tierUsed) {}

    public QuotaService(UserAiQuotaStateRepository repo, EntitlementService entitlementService) {
        this.repo = repo;
        this.entitlementService = entitlementService;
    }

    /**
     * ✅ 新版：由外部傳入已解析好的 entitlement tier，避免重複查詢
     */
    @Transactional
    public Decision consumeOperationOrThrow(
            Long userId,
            EntitlementService.Tier tier,
            ZoneId userTz,
            Instant nowUtc
    ) {
        ZoneId effectiveTz = (userTz == null) ? ZoneId.of("UTC") : userTz;

        boolean isTrial = (tier == EntitlementService.Tier.TRIAL);
        boolean isPaid = (tier == EntitlementService.Tier.MONTHLY || tier == EntitlementService.Tier.YEARLY);

        if (!isTrial && !isPaid) {
            throw new SubscriptionRequiredException("SUBSCRIPTION_REQUIRED");
        }

        int premiumLimit = isTrial ? trialPremiumLimit : paidPremiumLimit;
        int totalLimit = isTrial ? trialTotalLimit : paidTotalLimit;

        UserAiQuotaStateEntity s = getOrCreateStateForUpdate(userId, effectiveTz, nowUtc);

        CooldownReason reason = parseReason(s.getCooldownReason());
        boolean cooldownJustExpired = false;

        String dk = dayKey(nowUtc, effectiveTz);
        if (!dk.equals(s.getDailyKey())) {
            s.setDailyKey(dk);
            s.setDailyCount(0);

            if (isTrial && reason == CooldownReason.OVER_QUOTA) {
                clearOverQuotaCooldown(s);
            }
        }

        String mk = monthKey(nowUtc, effectiveTz);
        if (!mk.equals(s.getMonthlyKey())) {
            s.setMonthlyKey(mk);
            s.setMonthlyCount(0);

            if (isPaid && reason == CooldownReason.OVER_QUOTA) {
                clearOverQuotaCooldown(s);
            }
        }

        reason = parseReason(s.getCooldownReason());

        if (s.getNextAllowedAtUtc() != null) {
            if (nowUtc.isBefore(s.getNextAllowedAtUtc())) {
                Instant next = s.getNextAllowedAtUtc();
                throw new CooldownActiveException(
                        "COOLDOWN_ACTIVE",
                        next,
                        (int) Duration.between(nowUtc, next).getSeconds(),
                        clampStrikes(s.getCooldownStrikes()),
                        reason
                );
            } else {
                cooldownJustExpired = true;
                s.setNextAllowedAtUtc(null);

                if (reason != CooldownReason.OVER_QUOTA) {
                    s.setCooldownReason(null);
                    s.setCooldownStrikes(0);
                }
            }
        }

        if (s.getForceLowUntilUtc() != null && !nowUtc.isBefore(s.getForceLowUntilUtc())) {
            s.setForceLowUntilUtc(null);
        }

        int used = isTrial ? (nz(s.getDailyCount()) + 1) : (nz(s.getMonthlyCount()) + 1);

        if (used > totalLimit) {
            if (cooldownJustExpired && reason == CooldownReason.OVER_QUOTA) {
                if (isTrial) {
                    s.setDailyCount(used);
                } else {
                    s.setMonthlyCount(used);
                }

                s.setForceLowUntilUtc(nowUtc.plusSeconds(60));
                repo.save(s);
                return new Decision(ModelTier.MODEL_TIER_LOW);
            }

            int strikes = Math.min(3, nz(s.getCooldownStrikes()) + 1);
            s.setCooldownStrikes(strikes);

            int minutes = Math.min(maxCooldownMinutes, strikes * cooldownStepMinutes);
            Instant next = nowUtc.plus(Duration.ofMinutes(minutes));

            s.setNextAllowedAtUtc(next);
            s.setCooldownReason(CooldownReason.OVER_QUOTA.name());
            repo.save(s);

            throw new CooldownActiveException(
                    "COOLDOWN_ACTIVE",
                    next,
                    (int) Duration.between(nowUtc, next).getSeconds(),
                    strikes,
                    CooldownReason.OVER_QUOTA
            );
        }

        if (isTrial) {
            s.setDailyCount(used);
        } else {
            s.setMonthlyCount(used);
        }

        ModelTier tierUsed = (used <= premiumLimit)
                ? ModelTier.MODEL_TIER_HIGH
                : ModelTier.MODEL_TIER_LOW;

        if (s.getForceLowUntilUtc() != null && nowUtc.isBefore(s.getForceLowUntilUtc())) {
            tierUsed = ModelTier.MODEL_TIER_LOW;
        }

        repo.save(s);
        return new Decision(tierUsed);
    }

    /**
     * ✅ 舊版 wrapper：保留相容
     */
    @Transactional
    public Decision consumeOperationOrThrow(Long userId, ZoneId userTz, Instant nowUtc) {
        EntitlementService.Tier tier = entitlementService.resolveTier(userId, nowUtc);
        return consumeOperationOrThrow(userId, tier, userTz, nowUtc);
    }

    private UserAiQuotaStateEntity getOrCreateStateForUpdate(Long userId, ZoneId userTz, Instant nowUtc) {
        for (int i = 0; i < 3; i++) {
            var existing = repo.findForUpdate(userId);
            if (existing.isPresent()) {
                return existing.get();
            }

            try {
                repo.saveAndFlush(newQuotaState(userId, userTz, nowUtc));
            } catch (DataIntegrityViolationException ignored) {
                // 另一個並發請求剛好先建立成功，下一輪重抓即可
            }
        }
        return repo.findForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("QUOTA_STATE_CREATE_RACE"));
    }

    private UserAiQuotaStateEntity newQuotaState(Long userId, ZoneId userTz, Instant nowUtc) {
        UserAiQuotaStateEntity n = new UserAiQuotaStateEntity();
        n.setUserId(userId);
        n.setDailyKey(dayKey(nowUtc, userTz));
        n.setMonthlyKey(monthKey(nowUtc, userTz));
        n.setDailyCount(0);
        n.setMonthlyCount(0);
        n.setCooldownStrikes(0);
        n.setNextAllowedAtUtc(null);
        n.setForceLowUntilUtc(null);
        n.setCooldownReason(null);
        return n;
    }

    private static CooldownReason parseReason(String s) {
        try {
            return (s == null) ? null : CooldownReason.valueOf(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String dayKey(Instant nowUtc, ZoneId tz) {
        return DAY_KEY_FMT.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static String monthKey(Instant nowUtc, ZoneId tz) {
        return MONTH_KEY_FMT.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static void clearOverQuotaCooldown(UserAiQuotaStateEntity s) {
        s.setCooldownStrikes(0);
        s.setCooldownReason(null);
        s.setNextAllowedAtUtc(null);
        s.setForceLowUntilUtc(null); // ✅ 新週期一起清掉，避免跨日殘留 forced LOW
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static int clampStrikes(Integer v) {
        return Math.min(3, Math.max(1, nz(v)));
    }
}
