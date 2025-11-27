package com.calai.backend.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    private final Path root;

    public LocalImageStorage(
            @Value("${app.storage.weight-photos-dir:uploads/weight-photos}") String dir
    ) throws IOException {
        this.root = Paths.get(dir);
        Files.createDirectories(root);
        log.info("Weight photo upload dir = {}", root.toAbsolutePath());
    }

    /** 檔名：yyyyMMdd_<userId>_<uuid>.<ext> */
    public String save(Long userId, LocalDate logDate, MultipartFile file, String ext) throws IOException {
        Files.createDirectories(root);

        String date = logDate.format(DATE_FMT);
        String name = date + "_" + userId + "_" + UUID.randomUUID() + "." + ext;

        Path dst = root.resolve(name).normalize();
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return PUBLIC_PREFIX + name;
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
            log.warn("Delete photo failed: url={}, err={}", photoUrl, e.toString());
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
