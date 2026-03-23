package com.calai.backend.foodlog.web.advice;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class FoodLogErrorStatusResolverTest {

    @Test
    void should_resolve_not_found() {
        assertThat(FoodLogErrorStatusResolver.resolve("FOOD_LOG_NOT_FOUND"))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void should_resolve_gone() {
        assertThat(FoodLogErrorStatusResolver.resolve("FOOD_LOG_DELETED"))
                .isEqualTo(HttpStatus.GONE);
    }

    @Test
    void should_resolve_conflict_group() {
        assertThat(FoodLogErrorStatusResolver.resolve("FOOD_LOG_NOT_READY"))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(FoodLogErrorStatusResolver.resolve("FOOD_LOG_NOT_RETRYABLE"))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(FoodLogErrorStatusResolver.resolve("OVERRIDE_VALUE_INVALID"))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void should_resolve_bad_request_group() {
        assertThat(FoodLogErrorStatusResolver.resolve("FILE_REQUIRED"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(FoodLogErrorStatusResolver.resolve("BARCODE_INVALID"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(FoodLogErrorStatusResolver.resolve("BAD_REQUEST"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_resolve_payload_too_large_group() {
        assertThat(FoodLogErrorStatusResolver.resolve("FILE_TOO_LARGE"))
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(FoodLogErrorStatusResolver.resolve("IMAGE_TOO_LARGE"))
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void should_resolve_service_unavailable() {
        assertThat(FoodLogErrorStatusResolver.resolve("PROVIDER_NOT_AVAILABLE"))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void should_fallback_to_bad_request_for_unknown_code() {
        assertThat(FoodLogErrorStatusResolver.resolve("UNKNOWN_CODE"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_fallback_to_bad_request_for_null_or_blank() {
        assertThat(FoodLogErrorStatusResolver.resolve(null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(FoodLogErrorStatusResolver.resolve(""))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(FoodLogErrorStatusResolver.resolve("   "))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
