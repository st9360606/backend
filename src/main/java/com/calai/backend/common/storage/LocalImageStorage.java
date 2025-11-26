package com.calai.backend.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class LocalImageStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalImageStorage.class);

    private final Path root = Paths.get("uploads/weight-photos");

    public LocalImageStorage() throws IOException {
        Files.createDirectories(root);
        log.info("Weight photo upload dir = {}", root.toAbsolutePath()); // ✅ 你就知道它在哪C:/Users/wistronit/Desktop/Projects/calai/backend/backend/uploads/weight-photos
    }

    public String save(Long userId, MultipartFile file, String ext) throws IOException {
        Files.createDirectories(root); // ✅ 保險（避免 runtime working dir 變動）
        String name = "u" + userId + "-" + UUID.randomUUID() + "." + ext;
        Path dst = root.resolve(name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return "/static/weight-photos/" + name;
    }
}
