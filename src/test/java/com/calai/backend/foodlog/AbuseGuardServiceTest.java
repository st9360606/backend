package com.calai.backend.foodlog;

import com.calai.backend.foodlog.quota.config.AbuseGuardProperties;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.guard.AbuseGuardTxWriter;
import com.calai.backend.foodlog.web.error.CooldownActiveException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.*;

class AbuseGuardServiceTriggerTest {

    @Test
    void should_call_txWriter_when_ops_exceed_threshold_and_cache_hit_rate_low() {
        AbuseGuardProperties props = new AbuseGuardProperties();
        props.setEnabled(true);
        props.setHourOpsThreshold(1);
        props.setMinCacheHitRate(0.10);
        props.setDeviceSwitchThreshold(999);
        props.setWindow(Duration.ofHours(1));
        props.setKeyTtl(Duration.ofHours(2));

        AbuseGuardTxWriter txWriter = mock(AbuseGuardTxWriter.class);
        doThrow(new CooldownActiveException(
                "COOLDOWN_ACTIVE",
                Instant.parse("2026-02-15T00:30:00Z"),
                1800,
                3,
                com.calai.backend.foodlog.quota.model.CooldownReason.ABUSE
        )).when(txWriter).triggerAbuseAndThrow(anyLong(), any(), any());

        AbuseGuardService svc = new AbuseGuardService(props, txWriter);

        Instant now = Instant.parse("2026-02-15T00:00:00Z");
        ZoneId tz = ZoneId.of("Asia/Taipei");

        // 第一次：ops=1（若條件是 > threshold，還不觸發）
        try { svc.onOperationAttempt(1L, "dev-1", false, now, tz); } catch (CooldownActiveException ignore) {}

        // 第二次：應觸發
        try { svc.onOperationAttempt(1L, "dev-1", false, now.plusSeconds(1), tz); } catch (CooldownActiveException ignore) {}

        verify(txWriter, times(1)).triggerAbuseAndThrow(eq(1L), any(), eq(tz));
    }
}