package com.calai.backend.gemini;

import com.calai.backend.foodlog.model.ClientAction;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientActionMapperTest {

    private final ClientActionMapper mapper = new ClientActionMapper();

    @Test
    void fromErrorCode_should_map_correctly() {
        assertThat(mapper.fromErrorCode("PROVIDER_TIMEOUT")).isEqualTo(ClientAction.CHECK_NETWORK);
        assertThat(mapper.fromErrorCode("PROVIDER_NETWORK_ERROR")).isEqualTo(ClientAction.CHECK_NETWORK);

        assertThat(mapper.fromErrorCode("PROVIDER_BLOCKED")).isEqualTo(ClientAction.RETAKE_PHOTO);
        assertThat(mapper.fromErrorCode("PROVIDER_BAD_REQUEST")).isEqualTo(ClientAction.RETAKE_PHOTO);
        assertThat(mapper.fromErrorCode("PROVIDER_BAD_RESPONSE")).isEqualTo(ClientAction.RETAKE_PHOTO);

        assertThat(mapper.fromErrorCode("PROVIDER_AUTH_FAILED")).isEqualTo(ClientAction.CONTACT_SUPPORT);
        assertThat(mapper.fromErrorCode("GEMINI_API_KEY_MISSING")).isEqualTo(ClientAction.CONTACT_SUPPORT);
        assertThat(mapper.fromErrorCode("PROVIDER_NOT_AVAILABLE")).isEqualTo(ClientAction.CONTACT_SUPPORT);

        assertThat(mapper.fromErrorCode("PROVIDER_RATE_LIMITED")).isEqualTo(ClientAction.RETRY_LATER);
        assertThat(mapper.fromErrorCode(null)).isEqualTo(ClientAction.RETRY_LATER);
        assertThat(mapper.fromErrorCode("")).isEqualTo(ClientAction.RETRY_LATER);
    }

    @Test
    void fromWarnings_should_map_correctly() {
        assertThat(mapper.fromWarnings(List.of("NO_FOOD_DETECTED"))).isEqualTo(ClientAction.RETAKE_PHOTO);
        assertThat(mapper.fromWarnings(List.of("NON_FOOD_SUSPECT"))).isEqualTo(ClientAction.RETAKE_PHOTO);
        assertThat(mapper.fromWarnings(List.of("BLURRY_IMAGE"))).isEqualTo(ClientAction.RETAKE_PHOTO);

        assertThat(mapper.fromWarnings(List.of("UNKNOWN_FOOD"))).isEqualTo(ClientAction.ENTER_MANUALLY);

        assertThat(mapper.fromWarnings(List.of("LOW_CONFIDENCE"))).isEqualTo(ClientAction.RETRY_LATER);
        assertThat(mapper.fromWarnings(List.of())).isEqualTo(ClientAction.RETRY_LATER);
        assertThat(mapper.fromWarnings(null)).isEqualTo(ClientAction.RETRY_LATER);
    }
}
