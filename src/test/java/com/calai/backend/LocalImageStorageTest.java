package com.calai.backend;

import com.calai.backend.common.storage.LocalImageStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LocalImageStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void save_should_followNamingRule_and_deleteByUrl_should_work() throws Exception {
        LocalImageStorage storage = new LocalImageStorage(tempDir.toString());

        MockMultipartFile file = new MockMultipartFile(
                "photo", "a.jpg", "image/jpeg", "hello".getBytes()
        );

        String url = storage.save(12L, LocalDate.of(2025, 11, 27), file, "jpg");
        assertTrue(url.startsWith(LocalImageStorage.PUBLIC_PREFIX));

        String fn = storage.filenameFromUrl(url).orElseThrow();
        assertTrue(fn.startsWith("20251127_12_"));
        assertTrue(fn.endsWith(".jpg"));

        // 刪掉應成功（不丟例外）
        storage.deleteByUrl(url);
        storage.deleteByUrl(url); // 再刪一次也不應爆
    }
}
