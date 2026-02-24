package com.calai.backend.foodlog.quota.guard;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class AbuseGuardTxWriter {

    private final UserAiQuotaStateRepository stateRepo;
    private final AbuseGuardProperties props;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("yyyy-MM");

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = CooldownActiveException.class
    )
    public void triggerAbuseAndThrow(Long userId, Instant nowUtc, ZoneId userTz) {

        // 先查（有就鎖），沒有就先建立候選 entity
        UserAiQuotaStateEntity s = stateRepo.findForUpdate(userId)
                .orElseGet(() -> newState(userId, nowUtc, userTz));

        // 套用 abuse mutation（rollover / cooldown / strikes）
        applyAbuseMutation(s, nowUtc, userTz);

        try {
            s = stateRepo.save(s);

        } catch (DataIntegrityViolationException ex) {
            // ✅ 高併發首次建立 row 時可能撞到 unique key（另一條請求先 insert）
            // 這時重新查（for update）-> 再套一次 mutation -> save
            UserAiQuotaStateEntity locked = stateRepo.findForUpdate(userId).orElseThrow(() -> ex);

            applyAbuseMutation(locked, nowUtc, userTz);
            s = stateRepo.save(locked);
        }

        // ✅ 用實際生效的 cooldown（可能比 candidate 更晚，因為有 maxInstant 不縮短邏輯）
        Instant effectiveNextAllowed = s.getNextAllowedAtUtc();
        if (effectiveNextAllowed == null) {
            // 理論上不會發生，保底
            effectiveNextAllowed = nowUtc.plus(props.getCooldown());
        }

        // ✅ 用實際狀態上的 strikes（避免用 save 前暫存值）
        Integer strikes = s.getCooldownStrikes();
        int effectiveStrikes = (strikes == null || strikes <= 0) ? 3 : strikes;

        // ✅ 回傳給前端的 retry 秒數用實際剩餘秒數
        long retryAfterLong = effectiveNextAllowed.getEpochSecond() - nowUtc.getEpochSecond();
        if (retryAfterLong < 0) retryAfterLong = 0;
        if (retryAfterLong > Integer.MAX_VALUE) retryAfterLong = Integer.MAX_VALUE;
        int retryAfterSec = (int) retryAfterLong;

        throw new CooldownActiveException(
                "COOLDOWN_ACTIVE",
                effectiveNextAllowed,
                retryAfterSec,
                effectiveStrikes,
                CooldownReason.ABUSE
        );
    }

    private UserAiQuotaStateEntity newState(Long userId, Instant nowUtc, ZoneId userTz) {
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

    private void applyAbuseMutation(UserAiQuotaStateEntity s, Instant nowUtc, ZoneId userTz) {
        // ✅ rollover
        String dk = dayKey(nowUtc, userTz);
        if (!dk.equals(s.getDailyKey())) {
            s.setDailyKey(dk);
            s.setDailyCount(0);
        }

        String mk = monthKey(nowUtc, userTz);
        if (!mk.equals(s.getMonthlyKey())) {
            s.setMonthlyKey(mk);
            s.setMonthlyCount(0);
        }

        Instant nextCooldownCandidate = nowUtc.plus(props.getCooldown());
        Instant forceLowUntilCandidate = nowUtc.plus(props.getForceLow());

        // ✅ 不縮短既有冷卻：取較晚時間
        s.setNextAllowedAtUtc(maxInstant(s.getNextAllowedAtUtc(), nextCooldownCandidate));
        s.setForceLowUntilUtc(maxInstant(s.getForceLowUntilUtc(), forceLowUntilCandidate));

        s.setCooldownReason(CooldownReason.ABUSE.name());

        // ✅ null-safe + 可遞增；首次 abuse 至少拉到 3
        Integer strikes = s.getCooldownStrikes();
        int currentStrikes = (strikes == null) ? 0 : strikes;
        int nextStrikes = (currentStrikes <= 0) ? 3 : Math.max(3, currentStrikes + 1);
        s.setCooldownStrikes(nextStrikes);
    }

    private static String dayKey(Instant nowUtc, ZoneId tz) {
        return DAY.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static String monthKey(Instant nowUtc, ZoneId tz) {
        return MON.format(ZonedDateTime.ofInstant(nowUtc, tz)) + "@" + tz.getId();
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}