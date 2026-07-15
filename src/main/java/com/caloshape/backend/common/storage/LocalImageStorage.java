package com.caloshape.backend.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Service
public class LocalImageStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalImageStorage.class);

    /** URL 前綴（你目前就是用這個） */
    public static final String PUBLIC_PREFIX = "/static/weight-photos/";
    public static final String PROTECTED_PREFIX = "/api/v1/weights/photos/";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    private final Path root;

    public LocalImageStorage(
            @Value("${app.storage.weight-photos-dir:uploads/weight-photos}") String dir
    ) throws IOException {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Weight photo storage initialized");
    }

    /** 檔名：yyyyMMdd_<uuid>.<ext>；不包含 userId。 */
    public String save(LocalDate logDate, byte[] bytes, String ext) throws IOException {
        Files.createDirectories(root);

        String date = logDate.format(DATE_FMT);
        String name = date + "_" + UUID.randomUUID() + "." + ext;

        Path dst = root.resolve(name).normalize();
        Files.write(dst, bytes, StandardOpenOption.CREATE_NEW);
        return PUBLIC_PREFIX + name;
    }

    public Optional<FileSystemResource> findResource(String filename) {
        if (filename == null || filename.isBlank() || !filename.matches("[A-Za-z0-9._-]+")) {
            return Optional.empty();
        }

        Path candidate = root.resolve(filename).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemResource(candidate));
    }

    public String protectedUrlFromStoredUrl(String storedUrl) {
        return filenameFromUrl(storedUrl)
                .map(filename -> PROTECTED_PREFIX + filename)
                .orElse(null);
    }

    public String contentTypeForFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    /** 由 photoUrl 反解檔名 */
    public Optional<String> filenameFromUrl(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) return Optional.empty();
        int idx = photoUrl.lastIndexOf('/');
        if (idx < 0 || idx == photoUrl.length() - 1) return Optional.empty();
        return Optional.of(photoUrl.substring(idx + 1));
    }

    /** 依 URL 刪檔（安靜模式） */
    public void deleteByUrlQuietly(String photoUrl) {
        try {
            deleteByUrl(photoUrl);
        } catch (Exception e) {
            log.warn("Delete photo failed: errorType={}", e.getClass().getSimpleName());
        }
    }

    public void deleteByUrl(String photoUrl) throws IOException {
        var fnOpt = filenameFromUrl(photoUrl);
        if (fnOpt.isEmpty()) return;

        Path p = root.resolve(fnOpt.get()).normalize();
        if (!p.startsWith(root)) return; // 防路徑穿越

        Files.deleteIfExists(p);
    }

    /** 列出目前資料夾的所有檔名 */
    public Set<String> listAllFilenames() {
        if (!Files.exists(root)) return Set.of();

        try (Stream<Path> s = Files.list(root)) {
            Set<String> names = new HashSet<>();
            s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .forEach(names::add);
            return names;
        } catch (IOException e) {
            log.warn("List files failed: {}", e.toString());
            return Set.of();
        }
    }

    public String urlFromFilename(String filename) {
        return PUBLIC_PREFIX + filename;
    }
}
