package com.calai.backend.foodlog.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class LocalDiskStorageService implements StorageService {

    private final Path baseDir;

    public LocalDiskStorageService(@Value("${app.storage.local.base-dir:./data}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    @Override
    public SaveResult save(String objectKey, InputStream in, String contentType) throws Exception {
        Path path = resolve(objectKey);
        Files.createDirectories(path.getParent());

        MessageDigest md = MessageDigest.getInstance("SHA-256");

        long size = 0;
        try (DigestInputStream din = new DigestInputStream(in, md);
             OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buf = new byte[8192];
            int n;
            while ((n = din.read(buf)) >= 0) {
                out.write(buf, 0, n);
                size += n;
            }
        }

        String sha256 = HexFormat.of().formatHex(md.digest());
        return new SaveResult(objectKey, sha256, size, contentType);
    }

    @Override
    public OpenResult open(String objectKey) throws Exception {
        Path path = resolve(objectKey);
        if (!Files.exists(path)) throw new FileNotFoundException("OBJECT_NOT_FOUND: " + objectKey);

        // contentType 這裡先用 probeContentType（不一定準），你也可以直接存 DB 再回
        String ct = Files.probeContentType(path);
        long size = Files.size(path);
        InputStream in = Files.newInputStream(path, StandardOpenOption.READ);
        return new OpenResult(in, size, ct);
    }

    @Override
    public void delete(String objectKey) throws Exception {
        Path path = resolve(objectKey);
        Files.deleteIfExists(path);
    }

    private Path resolve(String objectKey) {
        // 防止 path traversal
        Path p = baseDir.resolve(objectKey).normalize();
        if (!p.startsWith(baseDir)) throw new SecurityException("Invalid objectKey");
        return p;
    }
}
