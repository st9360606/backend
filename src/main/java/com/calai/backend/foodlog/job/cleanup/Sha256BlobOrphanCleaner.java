package com.calai.backend.foodlog.job.cleanup;

import com.calai.backend.foodlog.repo.ImageBlobRepository;
import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.job.config.Sha256BlobOrphanCleanerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 清理正式 blob 區的孤兒檔案（user-blobs/sha256）
 * 清理條件（DB 對帳）：
 * 1) DB 無 row（image_blobs 不存在 userId + sha256）
 * 2) DB 有 row，但 ref_count <= 0
 * 安全策略：
 * - 預設 dry-run=true
 * - maxDeletePerRun 限額刪除
 * - minAge 避免剛建立檔案被誤判
 * 重要：
 * - 當 reason=REF_COUNT_NON_POSITIVE 且成功刪檔後，會同步呼叫 repo.deleteIfZero(...)
 *   避免留下 ref_count=0 row，造成未來 retain 時 DB row 存在但實體檔不存在。
 */
        @Slf4j
        @Component
        @ConditionalOnBean(LocalDiskStorageService.class)
        @ConditionalOnProperty(
                prefix = "app.storage.local.sha256-orphan-cleaner",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = false
        )
        public class Sha256BlobOrphanCleaner {

            private static final Pattern USER_DIR_PATTERN = Pattern.compile("^user-(\\d+)$");
            private static final Pattern SHA256_HEX_64 = Pattern.compile("^[0-9a-fA-F]{64}$");

            /** dry-run 詳細 candidate log 最多印幾筆，避免 log 爆量 */
            private static final int DRY_RUN_DETAIL_LOG_LIMIT = 20;

            private final LocalDiskStorageService storage;
            private final ImageBlobRepository repo;
            private final Sha256BlobOrphanCleanerProperties props;

            public Sha256BlobOrphanCleaner(LocalDiskStorageService storage,
                                           ImageBlobRepository repo,
                                           Sha256BlobOrphanCleanerProperties props) {
                this.storage = storage;
                this.repo = repo;
                this.props = props;
            }

            @Scheduled(
                    fixedDelayString = "${app.storage.local.sha256-orphan-cleaner.fixed-delay:PT6H}",
                    initialDelayString = "${app.storage.local.sha256-orphan-cleaner.initial-delay:PT5M}"
            )
            public void clean() {
                if (!props.isEnabled()) {
                    return;
                }

                final Path base = storage.getBaseDir().toAbsolutePath().normalize();
                if (!Files.exists(base) || !Files.isDirectory(base)) {
                    log.info("sha256 orphan cleaner skipped: base dir missing or not dir. base={}", base);
                    return;
                }

                final Instant now = Instant.now();
                final Duration minAge = props.getMinAge() == null ? Duration.ofHours(1) : props.getMinAge();
                final int maxDepth = Math.max(1, props.getMaxDepth());
                final int deleteBudget = Math.max(0, props.getMaxDeletePerRun());
                final String sha256Subdir = normalizeSubdir(props.getSha256Subdir());

                final AtomicInteger scannedUserRoots = new AtomicInteger(0);
                final AtomicInteger scannedFiles = new AtomicInteger(0);
                final AtomicInteger skippedYoungFiles = new AtomicInteger(0);
                final AtomicInteger skippedInvalidName = new AtomicInteger(0);
                final AtomicInteger skippedDbError = new AtomicInteger(0);
                final AtomicInteger keptFiles = new AtomicInteger(0);
                final AtomicInteger candidateMissingRow = new AtomicInteger(0);
                final AtomicInteger candidateRefCountNonPositive = new AtomicInteger(0);
                final AtomicInteger deletedFiles = new AtomicInteger(0);
                final AtomicInteger skippedByDeleteLimit = new AtomicInteger(0);
                final AtomicInteger deletedDirs = new AtomicInteger(0);

                // dry-run 詳細 log 節流計數
                final AtomicInteger dryRunDetailedLogged = new AtomicInteger(0);
                final AtomicInteger dryRunDetailedSuppressed = new AtomicInteger(0);

                log.info("sha256 orphan cleaner start. base={}, sha256Subdir={}, dryRun={}, minAge={}, maxDeletePerRun={}, maxDepth={}, deleteEmptyDirs={}",
                        base, sha256Subdir, props.isDryRun(), minAge, deleteBudget, maxDepth, props.isDeleteEmptyDirs());

                if (!props.isDryRun() && deleteBudget <= 0) {
                    log.warn("sha256 orphan cleaner running with dryRun=false but maxDeletePerRun<=0, no files will be deleted.");
                }

                try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, "user-*")) {
                    for (Path userDir : ds) {
                        if (!Files.isDirectory(userDir)) {
                            continue;
                        }

                        Long userId = parseUserId(userDir.getFileName() == null ? null : userDir.getFileName().toString());
                        if (userId == null) {
                            continue;
                        }

                        Path shaDir = resolveShaDirUnderUser(base, userDir, sha256Subdir);
                        if (shaDir == null || !Files.exists(shaDir) || !Files.isDirectory(shaDir)) {
                            continue;
                        }

                        scannedUserRoots.incrementAndGet();

                        cleanOneShaTree(
                                shaDir,
                                userId,
                                now,
                                minAge,
                                maxDepth,
                                deleteBudget,
                                scannedFiles,
                                skippedYoungFiles,
                                skippedInvalidName,
                                skippedDbError,
                                keptFiles,
                                candidateMissingRow,
                                candidateRefCountNonPositive,
                                deletedFiles,
                                skippedByDeleteLimit,
                                deletedDirs,
                                dryRunDetailedLogged,
                                dryRunDetailedSuppressed
                        );
                    }

                    log.info("sha256 orphan cleaner done. base={}, scannedUserRoots={}, scannedFiles={}, keptFiles={}, candidatesMissingRow={}, candidatesRefCountNonPositive={}, deletedFiles={}, skippedYoungFiles={}, skippedInvalidName={}, skippedDbError={}, skippedByDeleteLimit={}, deletedDirs={}, dryRunDetailLogged={}, dryRunDetailSuppressed={}",
                            base,
                            scannedUserRoots.get(),
                            scannedFiles.get(),
                            keptFiles.get(),
                            candidateMissingRow.get(),
                            candidateRefCountNonPositive.get(),
                            deletedFiles.get(),
                            skippedYoungFiles.get(),
                            skippedInvalidName.get(),
                            skippedDbError.get(),
                            skippedByDeleteLimit.get(),
                            deletedDirs.get(),
                            dryRunDetailedLogged.get(),
                            dryRunDetailedSuppressed.get()
                    );

                } catch (Exception e) {
                    log.warn("sha256 orphan cleaner failed. base={}", base, e);
                }
            }

            private void cleanOneShaTree(
                    Path shaDir,
                    Long userId,
                    Instant now,
                    Duration minAge,
                    int maxDepth,
                    int deleteBudget,
                    AtomicInteger scannedFiles,
                    AtomicInteger skippedYoungFiles,
                    AtomicInteger skippedInvalidName,
                    AtomicInteger skippedDbError,
                    AtomicInteger keptFiles,
                    AtomicInteger candidateMissingRow,
                    AtomicInteger candidateRefCountNonPositive,
                    AtomicInteger deletedFiles,
                    AtomicInteger skippedByDeleteLimit,
                    AtomicInteger deletedDirs,
                    AtomicInteger dryRunDetailedLogged,
                    AtomicInteger dryRunDetailedSuppressed
            ) throws IOException {

                Files.walkFileTree(
                        shaDir,
                        EnumSet.noneOf(FileVisitOption.class),
                        maxDepth,
                        new SimpleFileVisitor<>() {

                            @Override
                            public @NonNull FileVisitResult visitFile(@NonNull Path file,
                                                                      @NonNull BasicFileAttributes attrs) {
                                if (!attrs.isRegularFile()) {
                                    return FileVisitResult.CONTINUE;
                                }

                                scannedFiles.incrementAndGet();

                                // 安全：略過太新的檔案，避免剛寫入/剛搬移就被誤判
                                try {
                                    Instant lm = attrs.lastModifiedTime().toInstant();
                                    if (Duration.between(lm, now).compareTo(minAge) < 0) {
                                        skippedYoungFiles.incrementAndGet();
                                        return FileVisitResult.CONTINUE;
                                    }
                                } catch (Exception e) {
                                    // 取不到 mtime 就略過，避免誤刪
                                    log.debug("sha256 orphan cleaner skip file because cannot read mtime. file={}", file, e);
                                    skippedYoungFiles.incrementAndGet();
                                    return FileVisitResult.CONTINUE;
                                }

                                ParsedBlobFile parsed = parseBlobFilename(file.getFileName() == null ? null : file.getFileName().toString());
                                if (parsed == null) {
                                    skippedInvalidName.incrementAndGet();
                                    log.debug("sha256 orphan cleaner skip invalid filename. file={}", file);
                                    return FileVisitResult.CONTINUE;
                                }

                                try {
                                    // ✅ 輕量查詢：只查 ref_count，不載入整個 Entity
                                    Integer rc = repo.getRefCount(userId, parsed.sha256());

                                    // DB 無 row -> 候選刪除（孤兒檔）
                                    if (rc == null) {
                                        candidateMissingRow.incrementAndGet();
                                        return deleteCandidateIfAllowed(
                                                OrphanReason.DB_ROW_MISSING,
                                                file,
                                                userId,
                                                parsed,
                                                deleteBudget,
                                                deletedFiles,
                                                skippedByDeleteLimit,
                                                dryRunDetailedLogged,
                                                dryRunDetailedSuppressed
                                        );
                                    }

                                    // DB 有 row 且 ref_count > 0 -> 保留
                                    if (rc > 0) {
                                        keptFiles.incrementAndGet();
                                        return FileVisitResult.CONTINUE;
                                    }

                                    // DB 有 row，但 ref_count <= 0 -> 候選刪除
                                    candidateRefCountNonPositive.incrementAndGet();
                                    return deleteCandidateIfAllowed(
                                            OrphanReason.REF_COUNT_NON_POSITIVE,
                                            file,
                                            userId,
                                            parsed,
                                            deleteBudget,
                                            deletedFiles,
                                            skippedByDeleteLimit,
                                            dryRunDetailedLogged,
                                            dryRunDetailedSuppressed
                                    );

                                } catch (Exception e) {
                                    skippedDbError.incrementAndGet();
                                    log.warn("sha256 orphan cleaner DB check failed. userId={}, sha256={}, file={}",
                                            userId, parsed.sha256(), file, e);
                                    return FileVisitResult.CONTINUE;
                                }
                            }

                            @Override
                            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, IOException exc) {
                                if (exc != null) {
                                    log.debug("sha256 orphan cleaner postVisitDirectory exception. dir={}", dir, exc);
                                }

                                if (!props.isDeleteEmptyDirs()) return FileVisitResult.CONTINUE;
                                if (dir.equals(shaDir)) return FileVisitResult.CONTINUE; // 不刪 sha256 root 本身

                                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                                    if (!ds.iterator().hasNext()) {
                                        if (Files.deleteIfExists(dir)) {
                                            deletedDirs.incrementAndGet();
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug("sha256 orphan cleaner delete empty dir failed. dir={}", dir, e);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        }
                );
            }

            private FileVisitResult deleteCandidateIfAllowed(OrphanReason reason,
                                                             Path file,
                                                             Long userId,
                                                             ParsedBlobFile parsed,
                                                             int deleteBudget,
                                                             AtomicInteger deletedFiles,
                                                             AtomicInteger skippedByDeleteLimit,
                                                             AtomicInteger dryRunDetailedLogged,
                                                             AtomicInteger dryRunDetailedSuppressed) {

                // ✅ dry-run：只印前 N 筆詳細 log，避免 log 爆量
                if (props.isDryRun()) {
                    if (dryRunDetailedLogged.get() < DRY_RUN_DETAIL_LOG_LIMIT) {
                        int n = dryRunDetailedLogged.incrementAndGet();
                        if (n <= DRY_RUN_DETAIL_LOG_LIMIT) {
                            log.info("sha256 orphan cleaner dry-run candidate. reason={}, userId={}, sha256={}, ext={}, file={}",
                                    reason, userId, parsed.sha256(), parsed.ext(), file);
                        } else {
                            dryRunDetailedSuppressed.incrementAndGet();
                        }
                    } else {
                        dryRunDetailedSuppressed.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }

                // ✅ 刪除額度控制（0 或負數視為本次不刪）
                if (deleteBudget <= 0 || deletedFiles.get() >= deleteBudget) {
                    skippedByDeleteLimit.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }

                try {
                    if (Files.deleteIfExists(file)) {
                        int n = deletedFiles.incrementAndGet();

                        // ✅ 關鍵修正：若 DB row 存在但 ref_count<=0，刪檔成功後同步刪 DB row
                        if (reason == OrphanReason.REF_COUNT_NON_POSITIVE) {
                            try {
                                int deletedRows = repo.deleteIfZero(userId, parsed.sha256());
                                log.info("sha256 orphan cleaner deleted DB zero-row after file delete. userId={}, sha256={}, deletedRows={}",
                                        userId, parsed.sha256(), deletedRows);
                            } catch (Exception dbEx) {
                                // 檔案已刪但 row 未刪，先記錄，避免靜默資料不一致
                                log.warn("sha256 orphan cleaner deleted file but failed to delete zero-row. userId={}, sha256={}, file={}",
                                        userId, parsed.sha256(), file, dbEx);
                            }
                        }

                        log.info("sha256 orphan cleaner deleted. reason={}, userId={}, sha256={}, ext={}, file={}, deletedCount={}",
                                reason, userId, parsed.sha256(), parsed.ext(), file, n);
                    }
                } catch (Exception e) {
                    log.warn("sha256 orphan cleaner delete file failed. reason={}, userId={}, sha256={}, ext={}, file={}",
                            reason, userId, parsed.sha256(), parsed.ext(), file, e);
                }

                return FileVisitResult.CONTINUE;
            }

            /**
             * 解析 user 目錄名稱：user-1 -> 1
             */
            private Long parseUserId(String fileName) {
                if (fileName == null || fileName.isBlank()) return null;
                Matcher m = USER_DIR_PATTERN.matcher(fileName);
                if (!m.matches()) return null;
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }

            /**
             * 解析 blob 檔名：
             * - 前 64 碼必須是 sha256 hex
             * - 剩餘部分視為 ext（可空，例如無副檔名）
             *
             * 範例：
             * - abc...(64).jpg -> sha256=abc...(64), ext=.jpg
             * - abc...(64)     -> sha256=abc...(64), ext=""
             */
            private ParsedBlobFile parseBlobFilename(String name) {
                if (name == null || name.isBlank()) return null;
                if (name.length() < 64) return null;

                String sha = name.substring(0, 64);
                if (!SHA256_HEX_64.matcher(sha).matches()) return null;

                String ext = name.length() > 64 ? name.substring(64) : "";
                return new ParsedBlobFile(sha.toLowerCase(), ext);
            }

            /**
             * sha256Subdir 必須是相對路徑（例如 blobs/sha256）
             */
            private Path resolveShaDirUnderUser(Path base, Path userDir, String sha256Subdir) {
                try {
                    Path sub = Paths.get(sha256Subdir).normalize();
                    if (sub.isAbsolute()) {
                        log.warn("sha256 orphan cleaner skip userDir because sha256Subdir is absolute. userDir={}, sha256Subdir={}",
                                userDir, sha256Subdir);
                        return null;
                    }

                    Path shaDir = userDir.resolve(sub).toAbsolutePath().normalize();
                    if (!shaDir.startsWith(base)) {
                        log.warn("sha256 orphan cleaner skip userDir because resolved shaDir not under base. base={}, userDir={}, shaDir={}",
                                base, userDir, shaDir);
                        return null;
                    }
                    return shaDir;
                } catch (Exception e) {
                    log.warn("sha256 orphan cleaner resolve sha dir failed. userDir={}, sha256Subdir={}", userDir, sha256Subdir, e);
                    return null;
                }
            }

            private String normalizeSubdir(String raw) {
                if (raw == null || raw.isBlank()) return "blobs/sha256";
                String s = raw.trim().replace('\\', '/');
                while (s.startsWith("/")) s = s.substring(1);
                return s.isBlank() ? "blobs/sha256" : s;
            }

            private enum OrphanReason {
                DB_ROW_MISSING,
                REF_COUNT_NON_POSITIVE
            }

            private record ParsedBlobFile(String sha256, String ext) {}
        }