package com.caloshape.backend.entitlement.rtdn;

import com.caloshape.backend.internal.InternalApiGuard;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GoogleRtdnRequestAuthenticatorTest {

    @Test
    void oidcModeUsesOnlyGoogleAuthorizationHeader() {
        Fixture fixture = fixture(true, true);

        fixture.authenticator.requireAuthorized("Bearer signed-token", "legacy-token");

        verify(fixture.oidcVerifier).requireValidAuthorization("Bearer signed-token");
        verify(fixture.internalApiGuard, never()).requireValidToken("legacy-token");
    }

    @Test
    void legacyModeUsesInternalTokenForLocalDevelopment() {
        Fixture fixture = fixture(false, true);

        fixture.authenticator.requireAuthorized(null, "legacy-token");

        verify(fixture.internalApiGuard).requireValidToken("legacy-token");
        verify(fixture.oidcVerifier, never()).requireValidAuthorization(null);
    }

    @Test
    void disabledAuthenticationFailsClosed() {
        Fixture fixture = fixture(false, false);

        assertThatThrownBy(() -> fixture.authenticator.requireAuthorized(null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) ex).getStatusCode().value()
                ).isEqualTo(503));
    }

    private static Fixture fixture(boolean oidcEnabled, boolean legacyEnabled) {
        GoogleRtdnAuthProperties properties = new GoogleRtdnAuthProperties();
        properties.setOidcEnabled(oidcEnabled);
        properties.setLegacyInternalTokenEnabled(legacyEnabled);

        GooglePubSubOidcVerifier oidcVerifier = mock(GooglePubSubOidcVerifier.class);
        InternalApiGuard internalApiGuard = mock(InternalApiGuard.class);
        GoogleRtdnRequestAuthenticator authenticator = new GoogleRtdnRequestAuthenticator(
                properties,
                oidcVerifier,
                internalApiGuard
        );
        return new Fixture(authenticator, oidcVerifier, internalApiGuard);
    }

    private record Fixture(
            GoogleRtdnRequestAuthenticator authenticator,
            GooglePubSubOidcVerifier oidcVerifier,
            InternalApiGuard internalApiGuard
    ) {}
}
