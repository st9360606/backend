package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.config.PlayReviewAccessProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayReviewAccessServiceTest {

    @Test
    void matchesOnlyConfiguredEmailWhenEnabled() {
        PlayReviewAccessProperties properties = validProperties();
        PlayReviewAccessService service = new PlayReviewAccessService(properties);

        assertThat(service.fixedCodeFor(" PLAY-REVIEW@CALOSHAPE.COM "))
                .contains("482731");
        assertThat(service.fixedCodeFor("someone@example.com"))
                .isEmpty();
    }

    @Test
    void remainsUnavailableWhenDisabled() {
        PlayReviewAccessProperties properties = validProperties();
        properties.setEnabled(false);

        PlayReviewAccessService service = new PlayReviewAccessService(properties);

        assertThat(service.fixedCodeFor("play-review@caloshape.com"))
                .isEmpty();
    }

    @Test
    void rejectsInvalidEnabledConfiguration() {
        PlayReviewAccessProperties properties = validProperties();
        properties.setCode("not-a-code");

        assertThatThrownBy(() -> new PlayReviewAccessService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 6 digits");
    }

    private static PlayReviewAccessProperties validProperties() {
        PlayReviewAccessProperties properties = new PlayReviewAccessProperties();
        properties.setEnabled(true);
        properties.setEmail("play-review@caloshape.com");
        properties.setCode("482731");
        properties.setEntitlementValidity(Duration.ofDays(3650));
        return properties;
    }
}
