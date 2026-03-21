package com.calai.backend.foodlog;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.guard.AbuseGuardTxWriter;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.web.error.CooldownActiveException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 單元測試重點：
 * - 驗證同一 device 在同一視窗內切換多個 user 達門檻時，會觸發 txWriter
 */
class AbuseGuardServiceDeviceSwitchTest {

    @Test
    void should_trigger_abuse_when_same_device_switches_across_users_over_threshold() {
        // given
        AbuseGuardProperties props = new AbuseGuardProperties();
        props.setEnabled(true);
        props.setWindow(Duration.ofHours(1));
        props.setKeyTtl(Duration.ofHours(2));
        props.setHourOpsThreshold(9999);      // 避免 user ops hit-rate 條件干擾
        props.setMinCacheHitRate(0.10);
        props.setDeviceSwitchThreshold(2);    // 測試用：2 個 user 即觸發

        AbuseGuardTxWriter txWriter = mock(AbuseGuardTxWriter.class);
        doThrow(new CooldownActiveException(
                "COOLDOWN_ACTIVE",
                Instant.parse("2026-02-24T12:30:00Z"),
                1800,
                3,
                CooldownReason.ABUSE
        )).when(txWriter).triggerAbuseAndThrow(anyLong(), any(Instant.class), any(ZoneId.class));

        AbuseGuardService svc = new AbuseGuardService(props, txWriter);

        Instant now = Instant.parse("2026-02-24T12:00:00Z");
        ZoneId tz = ZoneId.of("Asia/Taipei");

        // 第 1 個 user 使用同 device（不觸發）
        svc.onBarcodeAttempt(1L, "shared-device-001", now, tz);

        // 第 2 個 user 使用同 device（達 threshold=2，應觸發）
        assertThatThrownBy(() -> svc.onBarcodeAttempt(2L, "shared-device-001", now.plusSeconds(5), tz))
                .isInstanceOf(CooldownActiveException.class)
                .hasMessage("COOLDOWN_ACTIVE");

        verify(txWriter, times(1)).triggerAbuseAndThrow(eq(2L), any(Instant.class), eq(tz));
    }
}