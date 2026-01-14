package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class LocalTempBlobCleaner {

    private final LocalDiskStorageService storage;
    private final Duration keep = Duration.ofHours(6);

    public LocalTempBlobCleaner(LocalDiskStorageService storage) {
        this.storage = storage;
    }

    @Scheduled(fixedDelay = 600_000) // 10 分鐘
    public void clean() {
        try {
            Path base = storage.getBaseDir(); // 你需要在 LocalDiskStorageService 暴露 getter
            Path tmpDir = base.resolve("user-").getParent(); // 這行不對，見下方「你要怎麼改」

            // ✅ 你要做的是：直接掃 baseDir 下的 "**/blobs/tmp/**"
            Instant now = Instant.now();
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        String p = file.toString().replace('\\', '/');
                        if (!p.contains("/blobs/tmp/")) return FileVisitResult.CONTINUE;

                        Instant lm = attrs.lastModifiedTime().toInstant();
                        if (Duration.between(lm, now).compareTo(keep) > 0) {
                            Files.deleteIfExists(file);
                        }
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });

        } catch (Exception e) {
            log.warn("tmp cleaner failed", e);
        }
    }
}
