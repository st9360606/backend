package com.calai.backend.foodlog;

import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.*;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceGuardTest {

    @Test
    void createPhoto_releaseInFlight_whenUnsupportedFormat() throws Exception {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
        StorageService storage = mock(StorageService.class);
        ObjectMapper om = mock(ObjectMapper.class);
        QuotaService quota = mock(QuotaService.class);
        IdempotencyService idem = mock(IdempotencyService.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        UserInFlightLimiter inFlight = mock(UserInFlightLimiter.class);
        UserRateLimiter rateLimiter = mock(UserRateLimiter.class);

        // ✅ 新增：PostProcessor（建構子新增依賴）
        EffectivePostProcessor postProcessor = mock(EffectivePostProcessor.class);

        FoodLogService svc = new FoodLogService(
                repo, taskRepo, storage, om,
                quota, idem, blobService,
                inFlight, rateLimiter,
                postProcessor
        );

        when(idem.reserveOrGetExisting(anyLong(), anyString(), any())).thenReturn(null);
        doNothing().when(rateLimiter).checkOrThrow(anyLong(), any());
        doNothing().when(idem).failAndReleaseIfNeeded(anyLong(), anyString(), anyString(), anyString(), anyBoolean());

        // 隨便塞一個不是圖片的 bytes → ImageSniffer.detect 會回 null → UNSUPPORTED_IMAGE_FORMAT
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "x.bin",
                "application/octet-stream",
                new byte[]{1, 2, 3, 4, 5}
        );

        assertThrows(IllegalArgumentException.class, () ->
                svc.createPhoto(1L, "Asia/Taipei", null, file, "rid-1")
        );

        verify(rateLimiter).checkOrThrow(eq(1L), any());
        verify(inFlight).acquireOrThrow(1L);
        verify(inFlight).release(1L);
    }
}
