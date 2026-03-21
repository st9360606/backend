package com.calai.backend.foodlog.mapper;
import com.calai.backend.foodlog.model.ClientAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientActionMapperBarcodeTest {

    private final ClientActionMapper mapper = new ClientActionMapper();

    @Test
    void should_map_barcode_not_found_to_scan_again() {
        assertThat(mapper.fromErrorCode("BARCODE_NOT_FOUND"))
                .isEqualTo(ClientAction.SCAN_AGAIN);
    }

    @Test
    void should_map_barcode_lookup_failed_to_retry_later() {
        assertThat(mapper.fromErrorCode("BARCODE_LOOKUP_FAILED"))
                .isEqualTo(ClientAction.RETRY_LATER);
    }

    @Test
    void should_map_barcode_nutrition_unavailable_to_try_photo() {
        assertThat(mapper.fromErrorCode("BARCODE_NUTRITION_UNAVAILABLE"))
                .isEqualTo(ClientAction.TRY_PHOTO);
    }
}