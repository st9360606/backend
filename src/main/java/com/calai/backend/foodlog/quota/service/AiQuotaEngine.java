package com.calai.backend.foodlog.quota.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import com.calai.backend.foodlog.web.SubscriptionRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@RequiredArgsConstructor
@Service
public class AiQuotaEngine {

    private final UserAiQuotaStateRepository repo;
    private final EntitlementService entitlement;

    // Trial：daily 15/30
    @Value("${app.ai.quota.trial.premium:15}")
    private int trialPremiumLimit;

    @Value("${app.ai.quota.trial.total:30}")
    private int trialTotalLimit;

    // Paid：monthly 600/1000（MONTHLY/YEARLY 都算 paid）
    @Value("${app.ai.quota.paid.premium:600}")
    private int paidPremiumLimit;

    @Value("${app.ai.quota.paid.total:1000}")
    private int paidTotalLimit;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("yyyy-MM");

    public record Decision(ModelTier tierUsed) {}

    @Transactional
    public Decision consumeOperationOrThrow(Long userId, ZoneId userTz, Instant nowUtc) {

        EntitlementService.Tier tier = entitlement.resolveTier(userId, nowUtc);
        if (tier == EntitlementService.Tier.NONE) {
            throw new SubscriptionRequiredException("SUBSCRIPTION_REQUIRED", "GO_SUBSCRIBE");
        }

        boolean isTrial = (tier == EntitlementService.Tier.TRIAL);
        boolean isPaid  = (tier == EntitlementService.Tier.MONTHLY || tier == EntitlementService.Tier.YEARLY);

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

        // ✅ 0) 清理過期的 cooldown/forceLow（過期就清掉）
        if (s.getNextAllowedAtUtc() != null && !nowUtc.isBefore(s.getNextAllowedAtUtc())) {
            s.setNextAllowedAtUtc(null);
            // cooldownReason 只在沒有 forceLow 時清掉
            if (s.getForceLowUntilUtc() == null || !nowUtc.isBefore(s.getForceLowUntilUtc())) {
                s.setCooldownReason(null);
                s.setCooldownStrikes(0);
            }
        }
        if (s.getForceLowUntilUtc() != null && !nowUtc.isBefore(s.getForceLowUntilUtc())) {
            s.setForceLowUntilUtc(null);
            // 若也沒有 cooldown 了，reason 一起清
            if (s.getNextAllowedAtUtc() == null) {
                s.setCooldownReason(null);
                s.setCooldownStrikes(0);
            }
        }

        // ✅ 1) 換日/換月 reset 只重置 count；但「不要」把 ABUSE 的 cooldown 清掉
        String dk = dayKey(nowUtc, userTz);
        if (!dk.equals(s.getDailyKey())) {
            s.setDailyKey(dk);
            s.setDailyCount(0);

            // 只有 OVER_QUOTA 才因換日解除 cooldown（符合 daily quota 行為）
            if (parseReason(s.getCooldownReason()) == CooldownReason.OVER_QUOTA) {
                s.setCooldownStrikes(0);
                s.setNextAllowedAtUtc(null);
                s.setCooldownReason(null);
            }
        }

        String mk = monthKey(nowUtc, userTz);
        if (!mk.equals(s.getMonthlyKey())) {
            s.setMonthlyKey(mk);
            s.setMonthlyCount(0);

            // 只有 OVER_QUOTA 才因換月解除 cooldown（符合 monthly quota 行為）
            if (parseReason(s.getCooldownReason()) == CooldownReason.OVER_QUOTA) {
                s.setCooldownStrikes(0);
                s.setNextAllowedAtUtc(null);
                s.setCooldownReason(null);
            }
        }

        // ✅ 2) cooldown 期間直接擋
        if (s.getNextAllowedAtUtc() != null && nowUtc.isBefore(s.getNextAllowedAtUtc())) {
            int sec = (int) Duration.between(nowUtc, s.getNextAllowedAtUtc()).getSeconds();
            if (sec < 0) sec = 0;
            repo.save(s);

            throw new CooldownActiveException(
                    "COOLDOWN_ACTIVE",
                    s.getNextAllowedAtUtc(),
                    sec,
                    Math.min(3, Math.max(1, s.getCooldownStrikes())),
                    parseReason(s.getCooldownReason())
            );
        }

        // ✅ 3) Trial 用 dailyCount；Paid 用 monthlyCount
        int used = isTrial ? (s.getDailyCount() + 1) : (s.getMonthlyCount() + 1);

        // ✅ 4) 超過 total → strikes + cooldown 10/20/30（只給 OVER_QUOTA）
        if (used > totalLimit) {
            int strikes = s.getCooldownStrikes() + 1;
            s.setCooldownStrikes(strikes);

            int minutes = Math.min(30, strikes * 10);
            Instant next = nowUtc.plus(Duration.ofMinutes(minutes));

            s.setNextAllowedAtUtc(next);
            s.setCooldownReason(CooldownReason.OVER_QUOTA.name());
            repo.save(s);

            throw new CooldownActiveException(
                    "COOLDOWN_ACTIVE",
                    next,
                    (int) Duration.between(nowUtc, next).getSeconds(),
                    Math.min(3, strikes),
                    CooldownReason.OVER_QUOTA
            );
        }

        // ✅ 5) commit count
        if (isTrial) s.setDailyCount(used);
        else if (isPaid) s.setMonthlyCount(used);

        // ✅ 6) 分段決定 tier
        ModelTier tierUsed = (used <= premiumLimit) ? ModelTier.MODEL_TIER_HIGH : ModelTier.MODEL_TIER_LOW;

        // ✅ 7) forceLowUntilUtc 覆蓋 tier（ABUSE 強制 LOW）
        if (s.getForceLowUntilUtc() != null && nowUtc.isBefore(s.getForceLowUntilUtc())) {
            tierUsed = ModelTier.MODEL_TIER_LOW;
        }

        repo.save(s);
        return new Decision(tierUsed);
    }

    private static String dayKey(Instant nowUtc, ZoneId tz) {
        return DAY.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static String monthKey(Instant nowUtc, ZoneId tz) {
        return MON.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static CooldownReason parseReason(String raw) {
        try {
            return raw == null ? CooldownReason.OVER_QUOTA : CooldownReason.valueOf(raw);
        } catch (Exception e) {
            return CooldownReason.OVER_QUOTA;
        }
    }
}