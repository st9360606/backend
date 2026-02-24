package com.calai.backend.foodlog;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.guard.AbuseGuardTxWriter;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.*;

/**
 * 單元測試重點：
 * - 驗證 TxWriter 寫 DB state 的內容
 * - 驗證拋出的 CooldownActiveException 內容
 * - 驗證跨日 / 跨月 rollover 重置 key/count
 */
class AbuseGuardTxWriterTest {

    @Test
    void should_create_new_state_set_cooldown_forceLow_and_throw() {
        // given
        UserAiQuotaStateRepository repo = mock(UserAiQuotaStateRepository.class);

        AbuseGuardProperties props = new AbuseGuardProperties();
        props.setCooldown(Duration.ofMinutes(30));
        props.setForceLow(Duration.ofMinutes(30));

        AbuseGuardTxWriter writer = new AbuseGuardTxWriter(repo, props);

        when(repo.findForUpdate(1L)).thenReturn(Optional.empty());

        ArgumentCaptor<UserAiQuotaStateEntity> captor = ArgumentCaptor.forClass(UserAiQuotaStateEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant now = Instant.parse("2026-02-24T12:00:00Z");
        ZoneId tz = ZoneId.of("Asia/Taipei");

        // when
        CooldownActiveException ex = catchThrowableOfType(
                () -> writer.triggerAbuseAndThrow(1L, now, tz),
                CooldownActiveException.class
        );

        // then - exception
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("COOLDOWN_ACTIVE");
        assertThat(ex.cooldownReason()).isEqualTo(CooldownReason.ABUSE);
        assertThat(ex.cooldownSeconds()).isEqualTo(30 * 60);
        assertThat(ex.cooldownLevel()).isEqualTo(3);

        // then - saved entity
        UserAiQuotaStateEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);

        // now=2026-02-24T12:00:00Z => Asia/Taipei = 2026-02-24 20:00:00
        assertThat(saved.getDailyKey()).isEqualTo("2026-02-24@Asia/Taipei");
        assertThat(saved.getMonthlyKey()).isEqualTo("2026-02@Asia/Taipei");

        // 新建 state 預設 count = 0
        assertThat(saved.getDailyCount()).isEqualTo(0);
        assertThat(saved.getMonthlyCount()).isEqualTo(0);

        // ABUSE cooldown 寫入
        assertThat(saved.getCooldownReason()).isEqualTo("ABUSE");
        assertThat(saved.getCooldownStrikes()).isEqualTo(3);
        assertThat(saved.getNextAllowedAtUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        assertThat(saved.getForceLowUntilUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));

        verify(repo, times(1)).findForUpdate(1L);
        verify(repo, times(1)).save(any(UserAiQuotaStateEntity.class));
    }

    @Test
    void should_rollover_daily_and_monthly_keys_and_reset_counts_when_keys_changed() {
        // given
        UserAiQuotaStateRepository repo = mock(UserAiQuotaStateRepository.class);

        AbuseGuardProperties props = new AbuseGuardProperties();
        props.setCooldown(Duration.ofMinutes(30));
        props.setForceLow(Duration.ofMinutes(30));

        AbuseGuardTxWriter writer = new AbuseGuardTxWriter(repo, props);

        UserAiQuotaStateEntity existing = new UserAiQuotaStateEntity();
        existing.setUserId(1L);

        // 舊 key（故意設成前一天 / 前一月）
        existing.setDailyKey("2026-01-31@Asia/Taipei");
        existing.setMonthlyKey("2026-01@Asia/Taipei");
        existing.setDailyCount(17);
        existing.setMonthlyCount(222);

        existing.setCooldownStrikes(1);
        existing.setNextAllowedAtUtc(null);
        existing.setForceLowUntilUtc(null);
        existing.setCooldownReason(null);

        when(repo.findForUpdate(1L)).thenReturn(Optional.of(existing));

        ArgumentCaptor<UserAiQuotaStateEntity> captor = ArgumentCaptor.forClass(UserAiQuotaStateEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // 這個時間在台北時區會是 2026-02-01（剛好跨日跨月）
        Instant now = Instant.parse("2026-01-31T16:30:00Z"); // +08 => 2026-02-01 00:30
        ZoneId tz = ZoneId.of("Asia/Taipei");

        // when
        CooldownActiveException ex = catchThrowableOfType(
                () -> writer.triggerAbuseAndThrow(1L, now, tz),
                CooldownActiveException.class
        );

        // then
        assertThat(ex).isNotNull();
        assertThat(ex.cooldownReason()).isEqualTo(CooldownReason.ABUSE);

        UserAiQuotaStateEntity saved = captor.getValue();

        // ✅ rollover 後 key 要更新
        assertThat(saved.getDailyKey()).isEqualTo("2026-02-01@Asia/Taipei");
        assertThat(saved.getMonthlyKey()).isEqualTo("2026-02@Asia/Taipei");

        // ✅ rollover 後 count 要重置
        assertThat(saved.getDailyCount()).isEqualTo(0);
        assertThat(saved.getMonthlyCount()).isEqualTo(0);

        // ✅ strikes 至少 3
        assertThat(saved.getCooldownStrikes()).isEqualTo(3);

        // ✅ cooldown / forceLow 寫入
        assertThat(saved.getCooldownReason()).isEqualTo("ABUSE");
        assertThat(saved.getNextAllowedAtUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        assertThat(saved.getForceLowUntilUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void should_keep_higher_existing_strikes_when_already_greater_than_3() {
        // given
        UserAiQuotaStateRepository repo = mock(UserAiQuotaStateRepository.class);

        AbuseGuardProperties props = new AbuseGuardProperties();
        props.setCooldown(Duration.ofMinutes(30));
        props.setForceLow(Duration.ofMinutes(30));

        AbuseGuardTxWriter writer = new AbuseGuardTxWriter(repo, props);

        UserAiQuotaStateEntity existing = new UserAiQuotaStateEntity();
        existing.setUserId(99L);
        existing.setDailyKey("2026-02-24@Asia/Taipei");
        existing.setMonthlyKey("2026-02@Asia/Taipei");
        existing.setDailyCount(10);
        existing.setMonthlyCount(100);
        existing.setCooldownStrikes(5); // 已經大於 3

        when(repo.findForUpdate(99L)).thenReturn(Optional.of(existing));

        ArgumentCaptor<UserAiQuotaStateEntity> captor = ArgumentCaptor.forClass(UserAiQuotaStateEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant now = Instant.parse("2026-02-24T02:00:00Z");
        ZoneId tz = ZoneId.of("Asia/Taipei");

        // when
        CooldownActiveException ex = catchThrowableOfType(
                () -> writer.triggerAbuseAndThrow(99L, now, tz),
                CooldownActiveException.class
        );

        // then
        assertThat(ex).isNotNull();
        UserAiQuotaStateEntity saved = captor.getValue();

        // ✅ 不應被降成 3，應保留 5
        assertThat(saved.getCooldownStrikes()).isEqualTo(5);
        assertThat(saved.getCooldownReason()).isEqualTo("ABUSE");
    }
}