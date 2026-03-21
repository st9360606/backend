package com.calai.backend.foodlog.service.image;

public record ImageOpenResult(
        String objectKey,
        String contentType,
        long sizeBytes
) {
}
