package com.calai.backend;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.repo.UserDailyActivityRepository;
import com.calai.backend.users.activity.service.DailyActivityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class DailyActivityServiceTest {

    @Test
    void upsert_shouldOverwriteNulls_fullReplace() {
        var repo = mock(UserDailyActivityRepository.class);
        when(repo.findByUserIdAndLocalDate(1L, LocalDate.parse("2026-01-02")))
                .thenReturn(Optional.of(new UserDailyActivity()));

        var svc = new DailyActivityService(repo);

        svc.upsert(1L, new DailyActivityService.UpsertReq(
                LocalDate.parse("2026-01-02"),
                "Asia/Taipei",
                null,               // ✅ 覆蓋成 null
                null,               // ✅ 覆蓋成 null
                UserDailyActivity.IngestSource.HEALTH_CONNECT,
                "com.google.android.apps.fitness",
                "Google Fit"
        ));

        var captor = ArgumentCaptor.forClass(UserDailyActivity.class);
        verify(repo).save(captor.capture());

        var saved = captor.getValue();
        assertNull(saved.getSteps());
        assertNull(saved.getActiveKcal());
        assertEquals(UserDailyActivity.IngestSource.HEALTH_CONNECT, saved.getIngestSource());
        assertEquals("com.google.android.apps.fitness", saved.getDataOriginPackage());
    }

    @Test
    void upsert_dstSafe_endIsNextStartOfDay_notPlus24h() {
        var repo = mock(UserDailyActivityRepository.class);
        when(repo.findByUserIdAndLocalDate(anyLong(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var svc = new DailyActivityService(repo);

        // 2024-03-10 是 LA DST 開始日（一天 23 小時）
        svc.upsert(1L, new DailyActivityService.UpsertReq(
                LocalDate.parse("2024-03-10"),
                "America/Los_Angeles",
                1000L,
                123.0,
                UserDailyActivity.IngestSource.HEALTH_CONNECT,
                "android",
                "On-device"
        ));

        var captor = ArgumentCaptor.forClass(UserDailyActivity.class);
        verify(repo).save(captor.capture());

        var e = captor.getValue();
        long seconds = e.getDayEndUtc().getEpochSecond() - e.getDayStartUtc().getEpochSecond();

        // 23h = 82800s（DST）
        assertEquals(23 * 3600, seconds);
    }
}
