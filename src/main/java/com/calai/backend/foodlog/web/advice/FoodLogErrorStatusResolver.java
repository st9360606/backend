package com.calai.backend.foodlog.web.advice;

import org.springframework.http.HttpStatus;

public final class FoodLogErrorStatusResolver {

    private FoodLogErrorStatusResolver() {
    }

    public static HttpStatus resolve(String code) {
        if (code == null || code.isBlank()) {
            return HttpStatus.BAD_REQUEST;
        }

        return switch (code) {
            case "FOOD_LOG_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FOOD_LOG_DELETED" -> HttpStatus.GONE;

            case "FOOD_LOG_NOT_READY",
                 "FOOD_LOG_FAILED",
                 "FOOD_LOG_NOT_SAVABLE",
                 "FOOD_LOG_NOT_EDITABLE",
                 "FOOD_LOG_NOT_RETRYABLE",
                 "DATE_RANGE_REQUIRED",
                 "DATE_RANGE_INVALID",
                 "FIELD_KEY_INVALID",
                 "OVERRIDE_VALUE_INVALID",
                 "PAGE_SIZE_TOO_LARGE",
                 "IMAGE_OBJECT_KEY_MISSING" -> HttpStatus.CONFLICT;

            case "FILE_REQUIRED",
                 "UNSUPPORTED_IMAGE_FORMAT",
                 "UNSUPPORTED_CONTENT_TYPE",
                 "BARCODE_REQUIRED",
                 "BARCODE_INVALID",
                 "BAD_REQUEST",
                 "EMPTY_IMAGE" -> HttpStatus.BAD_REQUEST;

            case "FILE_TOO_LARGE",
                 "IMAGE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;

            case "PROVIDER_NOT_AVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;

            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
