package com.caloshape.backend.weight.service;

import com.caloshape.backend.common.storage.LocalImageStorage;
import com.caloshape.backend.users.profile.service.UserProfileService;
import com.caloshape.backend.weight.dto.LogWeightRequest;
import com.caloshape.backend.weight.repo.WeightHistoryRepo;
import com.caloshape.backend.weight.repo.WeightTimeseriesRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeightServicePhotoAccessTest {

    @TempDir
    Path tempDir;

    @Test
    void ownerCanOpenPhoto_butOtherUserCannot() throws Exception {
        WeightHistoryRepo history = mock(WeightHistoryRepo.class);
        LocalImageStorage storage = new LocalImageStorage(tempDir.toString());
        String storedUrl = storage.save(
                LocalDate.of(2026, 7, 14),
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00},
                "jpg"
        );
        String filename = storage.filenameFromUrl(storedUrl).orElseThrow();
        when(history.existsByUserIdAndPhotoUrl(7L, storedUrl)).thenReturn(true);
        when(history.existsByUserIdAndPhotoUrl(8L, storedUrl)).thenReturn(false);

        WeightService service = service(history, storage);

        var owned = service.findOwnedPhoto(7L, filename).orElseThrow();
        assertEquals("image/jpeg", owned.mediaType().toString());
        assertEquals(4L, owned.contentLength());
        assertTrue(service.findOwnedPhoto(8L, filename).isEmpty());
        assertTrue(service.findOwnedPhoto(7L, "../" + filename).isEmpty());
    }

    @Test
    void uploadRejectsFilenameAndContentTypeSpoofing() throws Exception {
        WeightHistoryRepo history = mock(WeightHistoryRepo.class);
        when(history.findByUserIdAndLogDate(anyLong(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        WeightService service = service(history, new LocalImageStorage(tempDir.toString()));
        MockMultipartFile spoofed = new MockMultipartFile(
                "photo",
                "looks-like-a-photo.jpg",
                "image/jpeg",
                "not-an-image".getBytes()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.logWithPhoto(
                        7L,
                        new LogWeightRequest(BigDecimal.valueOf(70), null, LocalDate.of(2026, 7, 14)),
                        ZoneOffset.UTC,
                        spoofed
                )
        );
    }

    private static WeightService service(WeightHistoryRepo history, LocalImageStorage storage) {
        return new WeightService(
                history,
                mock(WeightTimeseriesRepo.class),
                mock(UserProfileService.class),
                storage
        );
    }
}
