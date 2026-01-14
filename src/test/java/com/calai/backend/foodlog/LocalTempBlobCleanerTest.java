package com.calai.backend.foodlog;

import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.task.LocalTempBlobCleaner;
import com.calai.backend.foodlog.task.config.LocalTempBlobCleanerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalTempBlobCleanerTest {

    @TempDir
    Path temp;

    @Test
    void clean_should_delete_old_files_under_tmp_only() throws Exception {
        Path base = temp;

        Path tmpDir = base.resolve("blobs/tmp");
        Files.createDirectories(tmpDir.resolve("a/b"));

        Path safeDir = base.resolve("blobs/keep");
        Files.createDirectories(safeDir);
        Path safeFile = safeDir.resolve("keep.txt");
        Files.writeString(safeFile, "keep");

        Path oldFile = tmpDir.resolve("a/b/old.txt");
        Files.writeString(oldFile, "old");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(Duration.ofHours(7))));

        Path freshFile = tmpDir.resolve("fresh.txt");
        Files.writeString(freshFile, "fresh");
        Files.setLastModifiedTime(freshFile, FileTime.from(Instant.now().minus(Duration.ofMinutes(30))));

        LocalTempBlobCleanerProperties props = new LocalTempBlobCleanerProperties();
        props.setEnabled(true);
        props.setKeep(Duration.ofHours(6));
        props.setMaxDepth(20);
        props.setDeleteEmptyDirs(true);
        props.setTmpSubdir("blobs/tmp");

        // ✅ 不要繼承 LocalDiskStorageService：直接 mock getBaseDir()
        LocalDiskStorageService storage = mock(LocalDiskStorageService.class);
        when(storage.getBaseDir()).thenReturn(base);

        LocalTempBlobCleaner cleaner = new LocalTempBlobCleaner(storage, props);
        cleaner.clean();

        assertFalse(Files.exists(oldFile), "old file should be deleted");
        assertTrue(Files.exists(freshFile), "fresh file should remain");
        assertTrue(Files.exists(safeFile), "non-tmp file should remain");

        assertFalse(Files.exists(tmpDir.resolve("a/b")), "empty nested tmp dir should be deleted");
        assertTrue(Files.exists(tmpDir), "tmp root dir should remain");
    }
}
