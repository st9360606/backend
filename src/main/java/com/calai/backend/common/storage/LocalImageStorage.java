package com.calai.backend.common.storage;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class LocalImageStorage {
    private final Path root = Paths.get("uploads/weight-photos");

    public LocalImageStorage() throws IOException {
        Files.createDirectories(root);
    }

    public String save(Long userId, MultipartFile file, String ext) throws IOException {
        String name = "u" + userId + "-" + UUID.randomUUID() + "." + ext;
        Path dst = root.resolve(name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        // 這裡回傳相對 URL，供前端顯示
        return "/static/weight-photos/" + name;
    }
}
