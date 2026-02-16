package com.calai.backend.foodlog.quota.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

@Service
public class AiQuotaEngine {

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

    @Value("${app.ai.quota.paid.premium-limit:600}")
    private int paidPremiumLimit;

    @Value("${app.ai.quota.paid.total-limit:1000}")
    private int paidTotalLimit;

    public record Decision(ModelTier tierUsed) {}

    public AiQuotaEngine(UserAiQuotaStateRepository repo, EntitlementService entitlementService) {
        this.repo = repo;
        this.entitlementService = entitlementService;
    }

    @Transactional
    public Decision consumeOperationOrThrow(Long userId, ZoneId userTz, Instant nowUtc) {
        EntitlementService.Tier tier = entitlementService.resolveTier(userId, nowUtc);
        boolean isTrial = (tier == EntitlementService.Tier.TRIAL);
        boolean isPaid  = (tier == EntitlementService.Tier.MONTHLY || tier == EntitlementService.Tier.YEARLY);

        if (!isTrial && !isPaid) {
            throw new com.calai.backend.foodlog.web.SubscriptionRequiredException("SUBSCRIPTION_REQUIRED");
        }

        int premiumLimit = isTrial ? trialPremiumLimit : paidPremiumLimit;
        int totalLimit   = isTrial ? trialTotalLimit   : paidTotalLimit;

        UserAiQuotaStateEntity s = repo.findForUpdate(userId).orElseGet(() -> {
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
        });

        CooldownReason reason = parseReason(s.getCooldownReason());
        boolean cooldownJustExpired = false;

        // A) cooldown active => 429
        if (s.getNextAllowedAtUtc() != null) {
            if (nowUtc.isBefore(s.getNextAllowedAtUtc())) {
                Instant next = s.getNextAllowedAtUtc();
                throw new CooldownActiveException(
                        "COOLDOWN_ACTIVE",
                        next,
                        (int) Duration.between(nowUtc, next).getSeconds(),
                        Math.min(3, Math.max(1, s.getCooldownStrikes())),
                        reason
                );
            } else {
                // cooldown 到期：清 nextAllowedAtUtc，但「不要」清 strikes（至少 OVER_QUOTA 要保留）
                cooldownJustExpired = true;
                s.setNextAllowedAtUtc(null);
                // ABUSE/RATE_LIMIT 若你想在到期後歸零可自行加條件；OVER_QUOTA 不要歸零
            }
        }

        // B) forceLow 到期清掉（這個可以清）
        if (s.getForceLowUntilUtc() != null && !nowUtc.isBefore(s.getForceLowUntilUtc())) {
            s.setForceLowUntilUtc(null);
        }

        // C) 換日/換月 reset（只對 OVER_QUOTA：到新週期才歸零 strikes）
        String dk = dayKey(nowUtc, userTz);
        if (!dk.equals(s.getDailyKey())) {
            s.setDailyKey(dk);
            s.setDailyCount(0);
            if (parseReason(s.getCooldownReason()) == CooldownReason.OVER_QUOTA) {
                s.setCooldownStrikes(0);
                s.setCooldownReason(null);
                s.setNextAllowedAtUtc(null);
            }
        }
        String mk = monthKey(nowUtc, userTz);
        if (!mk.equals(s.getMonthlyKey())) {
            s.setMonthlyKey(mk);
            s.setMonthlyCount(0);
            if (parseReason(s.getCooldownReason()) == CooldownReason.OVER_QUOTA) {
                s.setCooldownStrikes(0);
                s.setCooldownReason(null);
                s.setNextAllowedAtUtc(null);
            }
        }

        int used = isTrial ? (s.getDailyCount() + 1) : (s.getMonthlyCount() + 1);

        // D) 超額：第一次(或下一次)先進 cooldown；但 cooldown 剛到期的「下一次」允許且強制 LOW
        if (used > totalLimit) {
            if (cooldownJustExpired && reason == CooldownReason.OVER_QUOTA) {
                // ✅ 下一次允許（強制 LOW）
                if (isTrial) s.setDailyCount(used);
                else s.setMonthlyCount(used);

                s.setForceLowUntilUtc(nowUtc.plusSeconds(60)); // 讓本次一定落在 LOW
                repo.save(s);
                return new Decision(ModelTier.MODEL_TIER_LOW);
            }

            int strikes = Math.min(3, s.getCooldownStrikes() + 1); // 只需要 1~3
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

        // E) 未超額：正常計數 + tier 分段
        if (isTrial) s.setDailyCount(used);
        else s.setMonthlyCount(used);

        ModelTier tierUsed = (used <= premiumLimit) ? ModelTier.MODEL_TIER_HIGH : ModelTier.MODEL_TIER_LOW;

        if (s.getForceLowUntilUtc() != null && nowUtc.isBefore(s.getForceLowUntilUtc())) {
            tierUsed = ModelTier.MODEL_TIER_LOW;
        }

        repo.save(s);
        return new Decision(tierUsed);
    }

    private static CooldownReason parseReason(String s) {
        try { return (s == null) ? null : CooldownReason.valueOf(s); }
        catch (Exception ignore) { return null; }
    }

    private static String dayKey(Instant nowUtc, ZoneId tz) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                       .format(java.time.ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static String monthKey(Instant nowUtc, ZoneId tz) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
                       .format(java.time.ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }
}