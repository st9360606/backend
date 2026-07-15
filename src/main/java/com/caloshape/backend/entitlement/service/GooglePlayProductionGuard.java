package com.caloshape.backend.entitlement.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Production safety guard for Google Play Billing test hooks.
 *
 * DEV fake subscription tokens are only allowed for local/dev builds.
 * In production, enabling fake tokens would allow non-Google Play tokens
 * to pass subscription verification, so the app must fail fast at startup.
 */
@RequiredArgsConstructor
@Configuration
@EnableConfigurationProperties(GooglePlayVerifierProperties.class)
public class GooglePlayProductionGuard {

    private final Environment environment;
    private final GooglePlayVerifierProperties props;
    private final PurchaseTokenCrypto purchaseTokenCrypto;

    @PostConstruct
    void validateProductionConfig() {
        if (!isProductionProfileEnabled()) {
            return;
        }
        if (props.isDevFakeTokensEnabled()) {
            throw new IllegalStateException(
                    "dev fake Google Play tokens must be disabled in production"
            );
        }
        boolean hasPath = hasText(props.getServiceAccountJsonPath());
        boolean hasBase64 = hasText(props.getServiceAccountJsonBase64());
        if (hasPath == hasBase64) {
            throw new IllegalStateException(
                    "Production requires exactly one Google Play service-account credential source"
            );
        }
        if (!purchaseTokenCrypto.enabled()) {
            throw new IllegalStateException(
                    "Google Play purchase-token encryption key must be valid in production"
            );
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isProductionProfileEnabled() {
        return environment.acceptsProfiles(Profiles.of("prod", "production"));
    }
}
