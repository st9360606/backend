package com.calai.backend.foodlog;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbuseGuardServiceTest {

    @Test
    void triggerAbuse_should_set_cooldown_and_forceLow_to_30m_and_throw_429() {
        // given
        UserAiQuotaStateRepository repo = mock(UserAiQuotaStateRepository.class);

        AbuseGuardProperties props = new AbuseGuardProperties();
        props.setEnabled(true);
        props.setCooldown(Duration.ofMinutes(30));
        props.setForceLow(Duration.ofMinutes(30));
        props.setHourOpsThreshold(1);     // 測試用：很快觸發
        props.setMinCacheHitRate(0.10);
        props.setDeviceSwitchThreshold(999);

        AbuseGuardService svc = new AbuseGuardService(repo, props);

        UserAiQuotaStateEntity s = new UserAiQuotaStateEntity();
        s.setUserId(1L);
        s.setDailyKey("2026-02-15@Asia/Taipei");
        s.setMonthlyKey("2026-02@Asia/Taipei");
        s.setDailyCount(0);
        s.setMonthlyCount(0);
        s.setCooldownStrikes(0);

        when(repo.findForUpdate(1L)).thenReturn(Optional.of(s));

        ArgumentCaptor<UserAiQuotaStateEntity> saved = ArgumentCaptor.forClass(UserAiQuotaStateEntity.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant now = Instant.parse("2026-02-15T00:00:00Z");

        // when / then
        CooldownActiveException ex = catchThrowableOfType(() ->
                        // 連續丟兩次 cacheHit=false，第二次 ops > threshold 就會觸發
                        svc.onOperationAttempt(1L, "bc-test-device", false, now, ZoneId.of("Asia/Taipei")),
                CooldownActiveException.class
        );

        // 注意：上面 threshold=1，第一次可能就觸發（看你目前 ops 判斷是 > 還是 >=）
        // 若你專案是 ops > threshold 才觸發，就呼叫兩次：
        if (ex == null) {
            ex = catchThrowableOfType(() ->
                            svc.onOperationAttempt(1L, "bc-test-device", false, now.plusSeconds(1), ZoneId.of("Asia/Taipei")),
                    CooldownActiveException.class
            );
        }

        assertThat(ex).isNotNull();
        assertThat(ex.cooldownReason()).isEqualTo(CooldownReason.ABUSE);

        UserAiQuotaStateEntity savedEntity = saved.getValue();
        assertThat(savedEntity.getCooldownReason()).isEqualTo("ABUSE");

        // ✅ 30m cooldown
        assertThat(savedEntity.getNextAllowedAtUtc()).isAfter(now.plusSeconds(60 * 29));
        assertThat(savedEntity.getNextAllowedAtUtc()).isBefore(now.plusSeconds(60 * 31));

        // ✅ 30m forced LOW
        assertThat(savedEntity.getForceLowUntilUtc()).isAfter(now.plusSeconds(60 * 29));
        assertThat(savedEntity.getForceLowUntilUtc()).isBefore(now.plusSeconds(60 * 31));
    }
}
