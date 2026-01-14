package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.task.config.LocalTempBlobCleanerProperties;
import lombok.extern.slf4j.Slf4j;
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
            fixedDelayString = "${app.storage.local.tmp-cleaner.fixed-delay:PT10M}",
            initialDelayString = "${app.storage.local.tmp-cleaner.initial-delay:PT1M}"
    )
    public void clean() {
        if (!props.isEnabled()) return;

        final Path base = storage.getBaseDir().toAbsolutePath().normalize();
        final Path tmpDir = base.resolve(props.getTmpSubdir()).toAbsolutePath().normalize();

        if (!tmpDir.startsWith(base)) {
            log.warn("tmp cleaner skipped: tmpDir not under base. base={}, tmpDir={}", base, tmpDir);
            return;
        }
        if (!Files.exists(tmpDir) || !Files.isDirectory(tmpDir)) return;

        final Instant now = Instant.now();
        final Duration keep = props.getKeep();
        final int maxDepth = Math.max(1, props.getMaxDepth());

        final AtomicInteger deletedFiles = new AtomicInteger(0);
        final AtomicInteger deletedDirs = new AtomicInteger(0);

        try {
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
                            if (!props.isDeleteEmptyDirs()) return FileVisitResult.CONTINUE;
                            if (dir.equals(tmpDir)) return FileVisitResult.CONTINUE;

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

            if (deletedFiles.get() > 0 || deletedDirs.get() > 0) {
                log.info("tmp cleaner done. tmpDir={}, deletedFiles={}, deletedDirs={}",
                        tmpDir, deletedFiles.get(), deletedDirs.get());
            }
        } catch (Exception e) {
            log.warn("tmp cleaner failed. tmpDir={}", tmpDir, e);
        }
    }
}
