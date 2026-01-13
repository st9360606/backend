package com.calai.backend.foodlog.storage;

import java.io.InputStream;

public interface StorageService {

    SaveResult save(String objectKey, InputStream in, String contentType) throws Exception;

    OpenResult open(String objectKey) throws Exception;

    void delete(String objectKey) throws Exception;

    /** ✅ Step 3.11：內容定址需要 */
    boolean exists(String objectKey) throws Exception;

    /** ✅ Step 3.11：temp -> blobKey 的原子搬移（LocalDisk 可用 move） */
    void move(String fromObjectKey, String toObjectKey) throws Exception;

    record SaveResult(String objectKey, String sha256, long sizeBytes, String contentType) {}

    record OpenResult(InputStream inputStream, long sizeBytes, String contentType) {}
}
