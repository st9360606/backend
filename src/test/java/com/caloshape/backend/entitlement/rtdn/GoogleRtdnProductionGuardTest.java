package com.caloshape.backend.entitlement.rtdn;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleRtdnProductionGuardTest {

    @Test
    void productionRejectsDisabledOidc() {
        GoogleRtdnAuthProperties properties = validProperties();
        properties.setOidcEnabled(false);

        assertThatThrownBy(() -> guard("prod", properties).validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OIDC must be enabled");
    }

    @Test
    void productionRejectsMissingAudienceOrServiceAccount() {
        GoogleRtdnAuthProperties missingAudience = validProperties();
        missingAudience.setOidcAudience(" ");
        GoogleRtdnAuthProperties missingEmail = validProperties();
        missingEmail.setOidcServiceAccountEmail(null);

        assertThatThrownBy(() -> guard("prod", missingAudience).validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience is required");
        assertThatThrownBy(() -> guard("prod", missingEmail).validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-account email is required");
    }

    @Test
    void productionRejectsLegacyInternalTokenFallback() {
        GoogleRtdnAuthProperties properties = validProperties();
        properties.setLegacyInternalTokenEnabled(true);

        assertThatThrownBy(() -> guard("prod", properties).validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be disabled in production");
    }

    @Test
    void productionAcceptsCompleteOidcConfiguration() {
        assertThatCode(() -> guard("prod", validProperties()).validateProductionConfig())
                .doesNotThrowAnyException();
    }

    @Test
    void developmentKeepsLegacyAuthenticationAvailable() {
        GoogleRtdnAuthProperties properties = new GoogleRtdnAuthProperties();
        properties.setLegacyInternalTokenEnabled(true);

        assertThatCode(() -> guard("dev", properties).validateProductionConfig())
                .doesNotThrowAnyException();
    }

    private static GoogleRtdnAuthProperties validProperties() {
        GoogleRtdnAuthProperties properties = new GoogleRtdnAuthProperties();
        properties.setOidcEnabled(true);
        properties.setOidcAudience("https://api.example.com/internal/google/rtdn");
        properties.setOidcServiceAccountEmail("caloshape-rtdn@example.iam.gserviceaccount.com");
        properties.setLegacyInternalTokenEnabled(false);
        return properties;
    }

    private static GoogleRtdnProductionGuard guard(
            String profile,
            GoogleRtdnAuthProperties properties
    ) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new GoogleRtdnProductionGuard(environment, properties);
    }
}
