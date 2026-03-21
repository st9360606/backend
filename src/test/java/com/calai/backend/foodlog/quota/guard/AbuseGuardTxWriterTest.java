package com.calai.backend.foodlog.quota.guard;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.web.error.CooldownActiveException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.*;

class AbuseGuardTxWriterTest {

    @Test
    void should_create_new_state_set_cooldown_forceLow_and_throw() {
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

        CooldownActiveException ex = catchThrowableOfType(
                CooldownActiveException.class,
                () -> writer.triggerAbuseAndThrow(1L, now, tz)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("COOLDOWN_ACTIVE");
        assertThat(ex.cooldownReason()).isEqualTo(CooldownReason.ABUSE);
        assertThat(ex.cooldownSeconds()).isEqualTo(30 * 60);
        assertThat(ex.cooldownLevel()).isEqualTo(3);

        UserAiQuotaStateEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getDailyKey()).isEqualTo("2026-02-24@Asia/Taipei");
        assertThat(saved.getMonthlyKey()).isEqualTo("2026-02@Asia/Taipei");
        assertThat(saved.getDailyCount()).isEqualTo(0);
        assertThat(saved.getMonthlyCount()).isEqualTo(0);
        assertThat(saved.getCooldownReason()).isEqualTo("ABUSE");
        assertThat(saved.getCooldownStrikes()).isEqualTo(3);
        assertThat(saved.getNextAllowedAtUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        assertThat(saved.getForceLowUntilUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));

        verify(repo, times(1)).findForUpdate(1L);
        verify(repo, times(1)).save(any(UserAiQuotaStateEntity.class));
    }

    @Test
    void should_rollover_daily_and_monthly_keys_and_reset_counts_when_keys_changed() {
        UserAiQuotaStateRepository repo = mock(UserAiQuotaStateRepository.class);

        AbuseGuardProperties props = new AbuseGuardProperties();
        props.setCooldown(Duration.ofMinutes(30));
        props.setForceLow(Duration.ofMinutes(30));

        AbuseGuardTxWriter writer = new AbuseGuardTxWriter(repo, props);

        UserAiQuotaStateEntity existing = new UserAiQuotaStateEntity();
        existing.setUserId(1L);
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

        Instant now = Instant.parse("2026-01-31T16:30:00Z");
        ZoneId tz = ZoneId.of("Asia/Taipei");

        CooldownActiveException ex = catchThrowableOfType(
                CooldownActiveException.class,
                () -> writer.triggerAbuseAndThrow(1L, now, tz)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.cooldownReason()).isEqualTo(CooldownReason.ABUSE);

        UserAiQuotaStateEntity saved = captor.getValue();
        assertThat(saved.getDailyKey()).isEqualTo("2026-02-01@Asia/Taipei");
        assertThat(saved.getMonthlyKey()).isEqualTo("2026-02@Asia/Taipei");
        assertThat(saved.getDailyCount()).isEqualTo(0);
        assertThat(saved.getMonthlyCount()).isEqualTo(0);
        assertThat(saved.getCooldownStrikes()).isGreaterThanOrEqualTo(3);
        assertThat(saved.getCooldownReason()).isEqualTo("ABUSE");
        assertThat(saved.getNextAllowedAtUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        assertThat(saved.getForceLowUntilUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void should_not_drop_strikes_to_3_when_existing_is_higher() {
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
        existing.setCooldownStrikes(5);

        when(repo.findForUpdate(99L)).thenReturn(Optional.of(existing));

        ArgumentCaptor<UserAiQuotaStateEntity> captor = ArgumentCaptor.forClass(UserAiQuotaStateEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant now = Instant.parse("2026-02-24T02:00:00Z");
        ZoneId tz = ZoneId.of("Asia/Taipei");

        CooldownActiveException ex = catchThrowableOfType(
                CooldownActiveException.class,
                () -> writer.triggerAbuseAndThrow(99L, now, tz)
        );

        assertThat(ex).isNotNull();

        UserAiQuotaStateEntity saved = captor.getValue();
        assertThat(saved.getCooldownStrikes()).isGreaterThanOrEqualTo(5);
        assertThat(saved.getCooldownReason()).isEqualTo("ABUSE");
        assertThat(saved.getNextAllowedAtUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        assertThat(saved.getForceLowUntilUtc()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }
}
