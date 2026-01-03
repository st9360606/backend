package com.calai.backend;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.repo.UserDailyActivityRepository;
import com.calai.backend.users.activity.service.DailyActivityService;
import com.calai.backend.weight.repo.WeightTimeseriesRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyActivityServiceTest {

    @Test
    void upsert_healthConnect_shouldComputeActiveKcal_fromLatestWeightAndSteps() {
        // Arrange
        UserDailyActivityRepository repo = mock(UserDailyActivityRepository.class);
        WeightTimeseriesRepo weightSeries = mock(WeightTimeseriesRepo.class);

        DailyActivityService svc = new DailyActivityService(repo, weightSeries);

        Long userId = 1L;
        LocalDate day = LocalDate.of(2026, 1, 3);

        when(repo.findByUserIdAndLocalDate(eq(userId), eq(day)))
                .thenReturn(Optional.empty());

        // 這裡的「最新體重 entity」型別，請依你的專案實際類別替換
        // 你目前 service 用 latest.get(0).getWeightKg()
        // 所以只要 stub 出 getWeightKg() 即可
        var weightEntity = mock(com.calai.backend.weight.entity.WeightTimeseries.class);
        when(weightEntity.getWeightKg()).thenReturn(BigDecimal.valueOf(80.0)); // 80kg
        when(weightSeries.findLatest(eq(userId), any(PageRequest.class)))
                .thenReturn(List.of(weightEntity));

        // save 直接回傳參數（或回 null 也行）
        when(repo.save(any(UserDailyActivity.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new DailyActivityService.UpsertReq(
                day,
                "Asia/Taipei",
                1000L,                        // steps
                null,                         // activeKcal payload
                UserDailyActivity.IngestSource.HEALTH_CONNECT,
                "com.google.android.apps.fitness", // dataOriginPackage 必填
                "Google Fit"
        );

        // Act
        svc.upsert(userId, req);

        // Assert (captor 抓 save 的 entity)
        ArgumentCaptor<UserDailyActivity> captor = ArgumentCaptor.forClass(UserDailyActivity.class);
        verify(repo).save(captor.capture());

        UserDailyActivity saved = captor.getValue();

        // 80 * 1000 * 0.0005 = 40.0 → round = 40
        assertEquals(40.0, saved.getActiveKcal());
        assertEquals(1000L, saved.getSteps());
        assertEquals(UserDailyActivity.IngestSource.HEALTH_CONNECT, saved.getIngestSource());
        assertEquals("com.google.android.apps.fitness", saved.getDataOriginPackage());
        assertEquals("Google Fit", saved.getDataOriginName());
    }

    @Test
    void upsert_healthConnect_missingDataOriginPackage_should400() {
        UserDailyActivityRepository repo = mock(UserDailyActivityRepository.class);
        WeightTimeseriesRepo weightSeries = mock(WeightTimeseriesRepo.class);

        DailyActivityService svc = new DailyActivityService(repo, weightSeries);

        var req = new DailyActivityService.UpsertReq(
                LocalDate.of(2026, 1, 3),
                "Asia/Taipei",
                1000L,
                null,
                UserDailyActivity.IngestSource.HEALTH_CONNECT,
                "   ",     // 缺 package
                null
        );

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> svc.upsert(1L, req)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("dataOriginPackage"));
        verify(repo, never()).save(any());
    }
}
