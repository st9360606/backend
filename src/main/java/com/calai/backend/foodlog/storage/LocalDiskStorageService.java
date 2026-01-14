package com.calai.backend.foodlog.storage;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

@Getter
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

    @Override
    public boolean exists(String objectKey) throws Exception {
        Path path = resolve(objectKey);
        return Files.exists(path);
    }

    @Override
    public void move(String fromObjectKey, String toObjectKey) throws Exception {
        Path from = resolve(fromObjectKey);
        Path to = resolve(toObjectKey);
        Files.createDirectories(to.getParent());

        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // fallback：非原子 move（本機 dev OK）
            Files.move(from, to);
        }
    }

    private Path resolve(String objectKey) {
        Path p = baseDir.resolve(objectKey).normalize();
        if (!p.startsWith(baseDir)) throw new SecurityException("Invalid objectKey");
        return p;
    }

}
