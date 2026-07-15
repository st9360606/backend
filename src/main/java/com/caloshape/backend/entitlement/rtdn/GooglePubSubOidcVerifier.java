package com.caloshape.backend.entitlement.rtdn;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Slf4j
@Component
public class GooglePubSubOidcVerifier {

    private static final String BEARER_PREFIX = "Bearer ";

    private final GoogleIdTokenVerifier googleRtdnIdTokenVerifier;
    private final GoogleRtdnAuthProperties properties;

    public void requireValidAuthorization(String authorizationHeader) {
        String rawToken = extractBearerToken(authorizationHeader);

        GoogleIdToken idToken;
        try {
            idToken = googleRtdnIdTokenVerifier.verify(rawToken);
        } catch (Exception ex) {
            log.warn("rtdn_oidc_verification_failed errorType={}", ex.getClass().getSimpleName());
            throw unauthorized();
        }

        if (idToken == null) {
            throw unauthorized();
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String expectedEmail = properties.getOidcServiceAccountEmail();
        if (payload == null
                || expectedEmail == null
                || expectedEmail.isBlank()
                || payload.getEmail() == null
                || !expectedEmail.equalsIgnoreCase(payload.getEmail())
                || !Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "RTDN_OIDC_IDENTITY_NOT_ALLOWED"
            );
        }
    }

    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(
                        true,
                        0,
                        BEARER_PREFIX,
                        0,
                        BEARER_PREFIX.length()
                )) {
            throw unauthorized();
        }

        String rawToken = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isBlank()) {
            throw unauthorized();
        }
        return rawToken;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_RTDN_OIDC_TOKEN"
        );
    }
}
