package com.caloshape.backend.foodlog.service.image;

public record ImageOpenResult(
        String objectKey,
        String contentType,
        long sizeBytes
) {
}
