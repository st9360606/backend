package com.calai.backend.foodlog;

import com.calai.backend.foodlog.repo.ImageBlobRepository;
import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.job.cleanup.Sha256BlobOrphanCleaner;
import com.calai.backend.foodlog.job.config.Sha256BlobOrphanCleanerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sha256BlobOrphanCleaner 單元測試（不啟 Spring）
 *
 * 測試重點：
 * 1) dry-run 不刪檔
 * 2) DB 無 row -> 刪檔
 * 3) ref_count <= 0 -> 刪檔 + deleteIfZero()
 * 4) ref_count > 0 -> 保留
 * 5) minAge 與 maxDeletePerRun 生效
 */
class Sha256BlobOrphanCleaner_Test {

    @TempDir
    Path tempDir; // JUnit 會自動建立與清理暫存資料夾

    private ImageBlobRepository repo;
    private LocalDiskStorageService storage;
    private Sha256BlobOrphanCleanerProperties props;
    private Sha256BlobOrphanCleaner cleaner;

    @BeforeEach
    void setUp() {
        // mock repository
        repo = Mockito.mock(ImageBlobRepository.class);

        // LocalDiskStorageService 指向 JUnit 暫存資料夾（模擬 ./data）
        storage = new LocalDiskStorageService(tempDir.toString());

        // 建立 properties（用真物件，方便直接 set）
        props = new Sha256BlobOrphanCleanerProperties();
        props.setEnabled(true);
        props.setDryRun(true); // 預設先 dry-run，個別測試再覆蓋
        props.setSha256Subdir("blobs/sha256");
        props.setMinAge(Duration.ofSeconds(1)); // 測試方便：很小
        props.setFixedDelay(Duration.ofHours(6));
        props.setInitialDelay(Duration.ofMinutes(5));
        props.setMaxDeletePerRun(100);
        props.setMaxDepth(8);
        props.setDeleteEmptyDirs(false);

        cleaner = new Sha256BlobOrphanCleaner(storage, repo, props);
    }

    @Test
    void dryRun_should_not_delete_file_when_db_row_missing() throws Exception {
        // Arrange
        String sha = repeat("a", 64);
        Path file = createShaBlobFile(1L, sha, ".jpg", Instant.now().minus(Duration.ofHours(2)));

        when(repo.getRefCount(eq(1L), eq(sha))).thenReturn(null); // DB 無 row => candidate

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(file), "dry-run 模式不應刪檔");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, never()).deleteIfZero(anyLong(), anyString());
    }

    @Test
    void nonDryRun_should_delete_file_when_db_row_missing() throws Exception {
        // Arrange
        props.setDryRun(false);

        String sha = repeat("b", 64);
        Path file = createShaBlobFile(1L, sha, ".png", Instant.now().minus(Duration.ofHours(2)));

        when(repo.getRefCount(eq(1L), eq(sha))).thenReturn(null); // DB 無 row

        // Act
        cleaner.clean();

        // Assert
        assertFalse(Files.exists(file), "DB 無 row 的孤兒檔應被刪除");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, never()).deleteIfZero(anyLong(), anyString()); // DB 無 row 不需要 deleteIfZero
    }

    @Test
    void nonDryRun_should_delete_file_and_delete_db_row_when_refCount_non_positive() throws Exception {
        // Arrange
        props.setDryRun(false);

        String sha = repeat("c", 64);
        Path file = createShaBlobFile(1L, sha, ".webp", Instant.now().minus(Duration.ofHours(2)));

        when(repo.getRefCount(eq(1L), eq(sha))).thenReturn(0); // ref_count <= 0
        when(repo.deleteIfZero(eq(1L), eq(sha))).thenReturn(1);

        // Act
        cleaner.clean();

        // Assert
        assertFalse(Files.exists(file), "ref_count<=0 的檔案應被刪除");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, times(1)).deleteIfZero(1L, sha); // ✅ 這是你這次修正的重點
    }

    @Test
    void should_keep_file_when_refCount_positive() throws Exception {
        // Arrange
        props.setDryRun(false);

        String sha = repeat("d", 64);
        Path file = createShaBlobFile(1L, sha, ".jpg", Instant.now().minus(Duration.ofHours(2)));

        when(repo.getRefCount(eq(1L), eq(sha))).thenReturn(3); // 正常被引用

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(file), "ref_count>0 應保留檔案");
        verify(repo, times(1)).getRefCount(1L, sha);
        verify(repo, never()).deleteIfZero(anyLong(), anyString());
    }

    @Test
    void should_skip_young_file_by_minAge() throws Exception {
        // Arrange
        props.setDryRun(false);
        props.setMinAge(Duration.ofHours(1));

        String sha = repeat("e", 64);
        Path file = createShaBlobFile(1L, sha, ".jpg", Instant.now().minus(Duration.ofMinutes(10))); // 太新

        // 即使 DB 無 row，也應先被 minAge 擋下，不該查 DB
        // （cleaner 的邏輯是先檢查 mtime，再 parse / 查 DB）

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(file), "太新的檔案應被略過");
        verify(repo, never()).getRefCount(anyLong(), anyString());
        verify(repo, never()).deleteIfZero(anyLong(), anyString());
    }

    @Test
    void should_respect_maxDeletePerRun_limit() throws Exception {
        // Arrange
        props.setDryRun(false);
        props.setMaxDeletePerRun(1); // 只允許刪 1 個

        String sha1 = repeat("1", 64);
        String sha2 = repeat("2", 64);

        Path f1 = createShaBlobFile(1L, sha1, ".jpg", Instant.now().minus(Duration.ofHours(2)));
        Path f2 = createShaBlobFile(1L, sha2, ".jpg", Instant.now().minus(Duration.ofHours(2)));

        when(repo.getRefCount(eq(1L), eq(sha1))).thenReturn(null);
        when(repo.getRefCount(eq(1L), eq(sha2))).thenReturn(null);

        // Act
        cleaner.clean();

        // Assert
        boolean f1Exists = Files.exists(f1);
        boolean f2Exists = Files.exists(f2);

        // 應該只刪掉其中一個（walk 順序可能依檔案系統不同，不保證哪一個先）
        assertNotEquals(f1Exists, f2Exists, "maxDeletePerRun=1 時應只刪一個檔案");

        verify(repo, times(1)).getRefCount(1L, sha1);
        verify(repo, times(1)).getRefCount(1L, sha2);
        verify(repo, never()).deleteIfZero(anyLong(), anyString()); // 因為 rc=null（DB 無 row）
    }

    @Test
    void should_skip_invalid_filename() throws Exception {
        // Arrange
        props.setDryRun(false);

        Path invalid = tempDir.resolve("user-1").resolve("blobs").resolve("sha256").resolve("not-a-sha-file.jpg");
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, "x");
        Files.setLastModifiedTime(invalid, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(invalid), "無效檔名應被略過，不應刪除");
        verify(repo, never()).getRefCount(anyLong(), anyString());
    }

    @Test
    void should_ignore_non_user_directory() throws Exception {
        // Arrange
        props.setDryRun(false);

        String sha = repeat("f", 64);
        Path file = tempDir.resolve("abc").resolve("blobs").resolve("sha256").resolve(sha + ".jpg");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        // Act
        cleaner.clean();

        // Assert
        assertTrue(Files.exists(file), "非 user-* 目錄不應被掃描");
        verify(repo, never()).getRefCount(anyLong(), anyString());
    }

    // =========================
    // Helper methods
    // =========================

    /**
     * 建立一個正式 blob 檔案：
     * {base}/user-{userId}/blobs/sha256/{sha256}{ext}
     */
    private Path createShaBlobFile(Long userId, String sha256, String ext, Instant mtime) throws Exception {
        Path file = tempDir
                .resolve("user-" + userId)
                .resolve("blobs")
                .resolve("sha256")
                .resolve(sha256 + ext);

        Files.createDirectories(file.getParent());
        Files.writeString(file, "dummy-image-bytes");
        Files.setLastModifiedTime(file, FileTime.from(mtime));
        return file;
    }

    /**
     * 產生指定長度字串，例如 repeat("a", 64)
     */
    private static String repeat(String ch, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}