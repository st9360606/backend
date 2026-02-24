package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.task.config.LocalTempBlobCleanerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@ConditionalOnBean(LocalDiskStorageService.class)
@ConditionalOnProperty(
        prefix = "app.storage.local.tmp-cleaner",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LocalTempBlobCleaner {

    private final LocalDiskStorageService storage;
    private final LocalTempBlobCleanerProperties props;

    public LocalTempBlobCleaner(LocalDiskStorageService storage, LocalTempBlobCleanerProperties props) {
        this.storage = storage;
        this.props = props;
    }

    @Scheduled(
            fixedDelayString = "${app.storage.local.tmp-cleaner.fixed-delay:PT1H}",
            initialDelayString = "${app.storage.local.tmp-cleaner.initial-delay:PT1M}"
    )
    public void clean() {
        if (!props.isEnabled()) return;

        final Path base = storage.getBaseDir().toAbsolutePath().normalize();
        if (!Files.exists(base) || !Files.isDirectory(base)) {
            log.info("tmp cleaner skipped: base dir missing or not dir. base={}", base);
            return;
        }

        final Instant now = Instant.now();
        final Duration keep = props.getKeep() == null ? Duration.ofHours(6) : props.getKeep();
        final int maxDepth = Math.max(1, props.getMaxDepth());
        final String tmpSubdir = normalizeTmpSubdir(props.getTmpSubdir());

        final AtomicInteger deletedFiles = new AtomicInteger(0);
        final AtomicInteger deletedDirs = new AtomicInteger(0);
        final AtomicInteger scannedTmpRoots = new AtomicInteger(0);

        log.info("tmp cleaner start. base={}, tmpSubdir={}, keep={}, maxDepth={}, deleteEmptyDirs={}",
                base, tmpSubdir, keep, maxDepth, props.isDeleteEmptyDirs());

        // ✅ temp 實際路徑：{base}/user-*/{tmpSubdir}，預設為 {base}/user-*/blobs/tmp
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, "user-*")) {
            for (Path userDir : ds) {
                if (!Files.isDirectory(userDir)) continue;

                Path tmpDir = resolveTmpDirUnderUser(base, userDir, tmpSubdir);
                if (tmpDir == null) continue;
                if (!Files.exists(tmpDir) || !Files.isDirectory(tmpDir)) continue;

                scannedTmpRoots.incrementAndGet();
                cleanOneTmpTree(tmpDir, now, keep, maxDepth, deletedFiles, deletedDirs);
            }

            log.info("tmp cleaner done. base={}, scannedTmpRoots={}, deletedFiles={}, deletedDirs={}",
                    base, scannedTmpRoots.get(), deletedFiles.get(), deletedDirs.get());

        } catch (Exception e) {
            log.warn("tmp cleaner failed. base={}", base, e);
        }
    }

    /**
     * tmpSubdir 必須是相對路徑（例如 blobs/tmp）
     */
    private Path resolveTmpDirUnderUser(Path base, Path userDir, String tmpSubdir) {
        try {
            Path sub = Paths.get(tmpSubdir).normalize();
            if (sub.isAbsolute()) {
                log.warn("tmp cleaner skip userDir because tmpSubdir is absolute. userDir={}, tmpSubdir={}", userDir, tmpSubdir);
                return null;
            }

            Path tmpDir = userDir.resolve(sub).toAbsolutePath().normalize();
            if (!tmpDir.startsWith(base)) {
                log.warn("tmp cleaner skip userDir because resolved tmpDir not under base. base={}, userDir={}, tmpDir={}",
                        base, userDir, tmpDir);
                return null;
            }
            return tmpDir;
        } catch (Exception e) {
            log.warn("tmp cleaner resolve tmp dir failed. userDir={}, tmpSubdir={}", userDir, tmpSubdir, e);
            return null;
        }
    }

    private String normalizeTmpSubdir(String raw) {
        if (raw == null || raw.isBlank()) return "blobs/tmp";
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        return s.isBlank() ? "blobs/tmp" : s;
    }

    private void cleanOneTmpTree(
            Path tmpDir,
            Instant now,
            Duration keep,
            int maxDepth,
            AtomicInteger deletedFiles,
            AtomicInteger deletedDirs
    ) throws IOException {

        Files.walkFileTree(
                tmpDir,
                EnumSet.noneOf(FileVisitOption.class),
                maxDepth,
                new SimpleFileVisitor<>() {

                    @Override
                    public @NonNull FileVisitResult visitFile(
                            @NonNull Path file,
                            @NonNull BasicFileAttributes attrs
                    ) {
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;

                        try {
                            Instant lm = attrs.lastModifiedTime().toInstant();
                            // 超過 keep 才刪檔
                            if (Duration.between(lm, now).compareTo(keep) > 0) {
                                if (Files.deleteIfExists(file)) {
                                    deletedFiles.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            log.debug("tmp cleaner: delete file failed. file={}", file, e);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, IOException exc) {
                        if (exc != null) {
                            log.debug("tmp cleaner: postVisitDirectory has exception. dir={}", dir, exc);
                        }

                        if (!props.isDeleteEmptyDirs()) return FileVisitResult.CONTINUE;
                        if (dir.equals(tmpDir)) return FileVisitResult.CONTINUE; // 不刪 tmp root 本身

                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                            if (!ds.iterator().hasNext()) {
                                if (Files.deleteIfExists(dir)) {
                                    deletedDirs.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            log.debug("tmp cleaner: delete dir failed. dir={}", dir, e);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                }
        );
    }
}

