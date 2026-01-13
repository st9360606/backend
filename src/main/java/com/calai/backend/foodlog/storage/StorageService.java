package com.calai.backend.foodlog.storage;

import java.io.InputStream;

public interface StorageService {

    SaveResult save(String objectKey, InputStream in, String contentType) throws Exception;

    OpenResult open(String objectKey) throws Exception;

    void delete(String objectKey) throws Exception;

    record SaveResult(String objectKey, String sha256, long sizeBytes, String contentType) {}

    record OpenResult(InputStream inputStream, long sizeBytes, String contentType) {}
}
