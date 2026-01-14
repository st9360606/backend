package com.calai.backend.foodlog;

import com.calai.backend.foodlog.service.cleanup.StorageCleanup;
import com.calai.backend.foodlog.storage.StorageService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class StorageCleanupTest {

    @Test
    void safeDeleteQuietly_nullOrBlank_shouldNotCallDelete() throws Exception {
        StorageService storage = mock(StorageService.class);

        StorageCleanup.safeDeleteQuietly(storage, null);
        StorageCleanup.safeDeleteQuietly(storage, "");
        StorageCleanup.safeDeleteQuietly(storage, "   ");

        verify(storage, never()).delete(anyString());
    }

    @Test
    void safeDeleteQuietly_validKey_shouldCallDelete() throws Exception {
        StorageService storage = mock(StorageService.class);

        StorageCleanup.safeDeleteQuietly(storage, "k1");

        verify(storage, times(1)).delete("k1");
    }

    @Test
    void safeDeleteQuietly_deleteThrows_shouldSwallow() throws Exception {
        StorageService storage = mock(StorageService.class);
        doThrow(new RuntimeException("boom")).when(storage).delete("k1");

        // 不應丟出例外
        StorageCleanup.safeDeleteQuietly(storage, "k1");

        verify(storage, times(1)).delete("k1");
    }
}
