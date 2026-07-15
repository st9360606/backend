package com.caloshape.backend.entitlement.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GooglePlayProductionGuardTest {

    @Test
    void productionRejectsDisabledPurchaseTokenCrypto() {
        PurchaseTokenCrypto crypto = mock(PurchaseTokenCrypto.class);
        when(crypto.enabled()).thenReturn(false);

        GooglePlayProductionGuard guard = guard("prod", false, crypto, true, false);

        assertThatThrownBy(guard::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption key must be valid in production");
    }

    @Test
    void productionRejectsDevFakeTokensEvenWithValidCrypto() {
        PurchaseTokenCrypto crypto = mock(PurchaseTokenCrypto.class);
        when(crypto.enabled()).thenReturn(true);

        GooglePlayProductionGuard guard = guard("prod", true, crypto, true, false);

        assertThatThrownBy(guard::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake Google Play tokens must be disabled");
    }

    @Test
    void productionAcceptsValidCryptoAndDisabledFakeTokens() {
        PurchaseTokenCrypto crypto = mock(PurchaseTokenCrypto.class);
        when(crypto.enabled()).thenReturn(true);

        assertThatCode(() -> guard("prod", false, crypto, true, false).validateProductionConfig())
                .doesNotThrowAnyException();
    }

    @Test
    void productionRejectsMissingCredentialSource() {
        PurchaseTokenCrypto crypto = mock(PurchaseTokenCrypto.class);
        when(crypto.enabled()).thenReturn(true);

        assertThatThrownBy(() -> guard("prod", false, crypto, false, false).validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one Google Play service-account credential source");
    }

    @Test
    void productionRejectsAmbiguousCredentialSources() {
        PurchaseTokenCrypto crypto = mock(PurchaseTokenCrypto.class);
        when(crypto.enabled()).thenReturn(true);

        assertThatThrownBy(() -> guard("prod", false, crypto, true, true).validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one Google Play service-account credential source");
    }

    @Test
    void developmentMayStartWithoutPurchaseTokenCrypto() {
        PurchaseTokenCrypto crypto = mock(PurchaseTokenCrypto.class);
        when(crypto.enabled()).thenReturn(false);

        assertThatCode(() -> guard("dev", true, crypto, false, false).validateProductionConfig())
                .doesNotThrowAnyException();
    }

    private static GooglePlayProductionGuard guard(
            String profile,
            boolean devFakeTokensEnabled,
            PurchaseTokenCrypto crypto,
            boolean hasPath,
            boolean hasBase64
    ) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        GooglePlayVerifierProperties properties = new GooglePlayVerifierProperties();
        properties.setDevFakeTokensEnabled(devFakeTokensEnabled);
        properties.setServiceAccountJsonPath(hasPath ? "C:/secrets/play.json" : "");
        properties.setServiceAccountJsonBase64(hasBase64 ? "encoded-json" : "");
        return new GooglePlayProductionGuard(environment, properties, crypto);
    }
}
