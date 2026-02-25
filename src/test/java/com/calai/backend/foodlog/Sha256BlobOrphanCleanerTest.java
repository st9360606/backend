package com.calai.backend.foodlog;

import com.calai.backend.foodlog.repo.ImageBlobRepository;
import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.task.Sha256BlobOrphanCleaner;
import com.calai.backend.foodlog.task.config.Sha256BlobOrphanCleanerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class Sha256BlobOrphanCleanerTest {

    @TempDir
    Path tempDir;

    @Test
    void dryRun_should_not_delete_orphan_file() throws Exception {
        // Arrange
        String sha = repeatHex("a"); // 64 chars
        Path file = createBlobFile(tempDir, 1L, sha, ".jpg");

        ImageBlobRepository repo = mock(ImageBlobRepository.class);
        when(repo.getRefCount(1L, sha)).thenReturn(null); // ✅ DB 無 row -> orphan candidate

        Sha256BlobOrphanCleaner cleaner = newCleaner(tempDir, repo, true);

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(file), "dry-run=true 不應刪檔");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, never()).deleteIfZero(anyLong(), anyString());
    }

    @Test
    void should_delete_orphan_file_when_db_row_missing() throws Exception {
        // Arrange
        String sha = repeatHex("b");
        Path file = createBlobFile(tempDir, 1L, sha, ".png");

        ImageBlobRepository repo = mock(ImageBlobRepository.class);
        when(repo.getRefCount(1L, sha)).thenReturn(null); // ✅ DB 無 row

        Sha256BlobOrphanCleaner cleaner = newCleaner(tempDir, repo, false);

        // Act
        cleaner.clean();

        // Assert
        assertFalse(Files.exists(file), "DB 無 row 的孤兒檔應被刪除");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, never()).deleteIfZero(anyLong(), anyString()); // DB 無 row 不需要 deleteIfZero
    }

    @Test
    void should_delete_file_when_refCount_is_zero() throws Exception {
        // Arrange
        String sha = repeatHex("c");
        Path file = createBlobFile(tempDir, 1L, sha, ".webp");

        ImageBlobRepository repo = mock(ImageBlobRepository.class);
        when(repo.getRefCount(1L, sha)).thenReturn(0);     // ✅ ref_count <= 0
        when(repo.deleteIfZero(1L, sha)).thenReturn(1);    // ✅ 若 cleaner 有做 DB row 清理

        Sha256BlobOrphanCleaner cleaner = newCleaner(tempDir, repo, false);

        // Act
        cleaner.clean();

        // Assert
        assertFalse(Files.exists(file), "ref_count<=0 的正式 blob 應被刪除");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, times(1)).deleteIfZero(1L, sha); // 若你的 cleaner 確實有呼叫 deleteIfZero
    }

    @Test
    void should_keep_file_when_refCount_positive() throws Exception {
        // Arrange
        String sha = repeatHex("d");
        Path file = createBlobFile(tempDir, 1L, sha, ".jpg");

        ImageBlobRepository repo = mock(ImageBlobRepository.class);
        when(repo.getRefCount(1L, sha)).thenReturn(2); // ✅ ref_count > 0 應保留

        Sha256BlobOrphanCleaner cleaner = newCleaner(tempDir, repo, false);

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(file), "ref_count>0 的正式 blob 不應被刪除");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, never()).deleteIfZero(anyLong(), anyString());
    }

    @Test
    void invalid_filename_should_be_ignored() throws Exception {
        // Arrange: 檔名不是 64hex 開頭，應被略過
        Path invalid = tempDir.resolve("user-1/blobs/sha256/not-a-sha-file.jpg");
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, "dummy");

        ImageBlobRepository repo = mock(ImageBlobRepository.class);
        Sha256BlobOrphanCleaner cleaner = newCleaner(tempDir, repo, false);

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(invalid), "無法解析的檔名應略過，不應刪除");
        verify(repo, never()).getRefCount(anyLong(), anyString()); // ✅ cleaner 不應查 DB
        verify(repo, never()).deleteIfZero(anyLong(), anyString());
    }

    // ===== helper methods =====

    private Sha256BlobOrphanCleaner newCleaner(Path baseDir, ImageBlobRepository repo, boolean dryRun) {
        LocalDiskStorageService storage = new LocalDiskStorageService(baseDir.toString());

        Sha256BlobOrphanCleanerProperties props = new Sha256BlobOrphanCleanerProperties();
        props.setEnabled(true);
        props.setDryRun(dryRun);
        props.setSha256Subdir("blobs/sha256");
        props.setMinAge(Duration.ZERO);          // 測試時不要因檔案太新被略過
        props.setFixedDelay(Duration.ofHours(6));
        props.setInitialDelay(Duration.ofMinutes(1));
        props.setMaxDeletePerRun(100);
        props.setMaxDepth(8);
        props.setDeleteEmptyDirs(false);

        return new Sha256BlobOrphanCleaner(storage, repo, props);
    }

    private Path createBlobFile(Path base, Long userId, String sha, String ext) throws Exception {
        Path file = base.resolve("user-" + userId + "/blobs/sha256/" + sha + ext);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "dummy-image-content");
        return file;
    }

    /**
     * 產生 64 長度十六進位字串（測試用）
     */
    private String repeatHex(String ch) {
        return ch.repeat(64); // a/b/c/d 都是合法 hex
    }
}