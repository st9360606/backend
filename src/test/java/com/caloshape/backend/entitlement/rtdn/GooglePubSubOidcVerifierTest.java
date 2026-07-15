package com.caloshape.backend.entitlement.rtdn;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GooglePubSubOidcVerifierTest {

    @Mock
    private GoogleIdTokenVerifier idTokenVerifier;

    private GooglePubSubOidcVerifier verifier;

    @BeforeEach
    void setUp() {
        GoogleRtdnAuthProperties properties = new GoogleRtdnAuthProperties();
        properties.setOidcServiceAccountEmail("caloshape-rtdn@example.iam.gserviceaccount.com");
        verifier = new GooglePubSubOidcVerifier(idTokenVerifier, properties);
    }

    @Test
    void missingBearerTokenIsUnauthorized() {
        assertThatThrownBy(() -> verifier.requireValidAuthorization(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertStatus((ResponseStatusException) ex, 401));
    }

    @Test
    void invalidGoogleSignatureOrAudienceIsUnauthorized() throws Exception {
        when(idTokenVerifier.verify("invalid-token")).thenReturn(null);

        assertThatThrownBy(() -> verifier.requireValidAuthorization("Bearer invalid-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertStatus((ResponseStatusException) ex, 401));
    }

    @Test
    void unexpectedServiceAccountIsForbidden() throws Exception {
        GoogleIdToken idToken = idToken(
                "other@example.iam.gserviceaccount.com",
                true
        );
        when(idTokenVerifier.verify("signed-token")).thenReturn(idToken);

        assertThatThrownBy(() -> verifier.requireValidAuthorization("Bearer signed-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertStatus((ResponseStatusException) ex, 403));
    }

    @Test
    void unverifiedEmailIsForbidden() throws Exception {
        GoogleIdToken idToken = idToken(
                "caloshape-rtdn@example.iam.gserviceaccount.com",
                false
        );
        when(idTokenVerifier.verify("signed-token")).thenReturn(idToken);

        assertThatThrownBy(() -> verifier.requireValidAuthorization("Bearer signed-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertStatus((ResponseStatusException) ex, 403));
    }

    @Test
    void expectedVerifiedServiceAccountIsAccepted() throws Exception {
        GoogleIdToken idToken = idToken(
                "caloshape-rtdn@example.iam.gserviceaccount.com",
                true
        );
        when(idTokenVerifier.verify("signed-token")).thenReturn(idToken);

        assertThatCode(() -> verifier.requireValidAuthorization("Bearer signed-token"))
                .doesNotThrowAnyException();
    }

    private static GoogleIdToken idToken(String email, boolean emailVerified) {
        GoogleIdToken token = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
                .setEmail(email)
                .setEmailVerified(emailVerified);
        when(token.getPayload()).thenReturn(payload);
        return token;
    }

    private static void assertStatus(ResponseStatusException exception, int expectedStatus) {
        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode().value())
                .isEqualTo(expectedStatus);
    }
}
