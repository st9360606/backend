package com.caloshape.backend.entitlement.rtdn;

import com.caloshape.backend.internal.InternalApiGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Component
public class GoogleRtdnRequestAuthenticator {

    private final GoogleRtdnAuthProperties properties;
    private final GooglePubSubOidcVerifier oidcVerifier;
    private final InternalApiGuard internalApiGuard;

    public void requireAuthorized(String authorizationHeader, String internalToken) {
        if (properties.isOidcEnabled()) {
            oidcVerifier.requireValidAuthorization(authorizationHeader);
            return;
        }

        if (properties.isLegacyInternalTokenEnabled()) {
            internalApiGuard.requireValidToken(internalToken);
            return;
        }

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RTDN_AUTH_NOT_CONFIGURED"
        );
    }
}
