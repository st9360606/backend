package com.calai.backend.foodlog.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class LocalDiskStorageServiceTest {

    @Test
    void save_should_return_sha_and_size() throws Exception {
        StorageService storage = new LocalDiskStorageService("./build/test-storage");

        byte[] bytes = "fake-image-bytes".getBytes();
        var saved = storage.save("u1/x/original.jpg", new ByteArrayInputStream(bytes), "image/jpeg");

        assertEquals("u1/x/original.jpg", saved.objectKey());
        assertEquals(bytes.length, saved.sizeBytes());
        assertNotNull(saved.sha256());
        assertEquals("image/jpeg", saved.contentType());

        var opened = storage.open(saved.objectKey());
        assertTrue(opened.sizeBytes() > 0);
        opened.inputStream().close();

        storage.delete(saved.objectKey());
    }
}
