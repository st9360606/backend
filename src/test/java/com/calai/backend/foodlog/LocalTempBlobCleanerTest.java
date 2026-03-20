package com.calai.backend.foodlog;

import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.job.cleanup.LocalTempBlobCleaner;
import com.calai.backend.foodlog.job.config.LocalTempBlobCleanerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LocalTempBlobCleanerTest {

    @TempDir
    Path temp;

    @Test
    void clean_should_delete_old_files_under_user_tmp_only() throws Exception {
        // Arrange
        Path base = temp;

        long userId = 1L;
        Path userDir = base.resolve("user-" + userId);

        // ✅ Cleaner 會掃：{base}/user-*/blobs/tmp
        Path tmpDir = userDir.resolve("blobs/tmp");
        Files.createDirectories(tmpDir.resolve("a/b"));

        // ✅ 安全區：同一個 user 底下，但不是 tmp
        Path safeDir = userDir.resolve("blobs/keep");
        Files.createDirectories(safeDir);
        Path safeFile = safeDir.resolve("keep.txt");
        Files.writeString(safeFile, "keep");

        // ✅ 舊檔：超過 keep(6h) → 應刪
        Path oldFile = tmpDir.resolve("a/b/old.txt");
        Files.writeString(oldFile, "old");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(Duration.ofHours(7))));

        // ✅ 新檔：未超過 keep → 保留
        Path freshFile = tmpDir.resolve("fresh.txt");
        Files.writeString(freshFile, "fresh");
        Files.setLastModifiedTime(freshFile, FileTime.from(Instant.now().minus(Duration.ofMinutes(30))));

        LocalTempBlobCleanerProperties props = new LocalTempBlobCleanerProperties();
        props.setEnabled(true);
        props.setKeep(Duration.ofHours(6));
        props.setMaxDepth(20);
        props.setDeleteEmptyDirs(true);
        props.setTmpSubdir("blobs/tmp"); // 相對於 userDir（user-*/{tmpSubdir}）

        // ✅ 建議：不要 mock，避免 Mockito agent 警告
        LocalDiskStorageService storage = new LocalDiskStorageService(base.toString());

        LocalTempBlobCleaner cleaner = new LocalTempBlobCleaner(storage, props);

        // Act
        cleaner.clean();

        // Assert
        assertFalse(Files.exists(oldFile), "old file should be deleted");
        assertTrue(Files.exists(freshFile), "fresh file should remain");
        assertTrue(Files.exists(safeFile), "non-tmp file should remain");

        // old.txt 刪掉後 a/b 應成空資料夾 → 應刪
        assertFalse(Files.exists(tmpDir.resolve("a/b")), "empty nested tmp dir should be deleted");

        // tmp root 不刪（cleaner 設計就是保留 root）
        assertTrue(Files.exists(tmpDir), "tmp root dir should remain");
    }
}