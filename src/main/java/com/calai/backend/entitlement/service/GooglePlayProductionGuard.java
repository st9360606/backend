package com.calai.backend.entitlement.service;

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

    @PostConstruct
    void validateProductionFakeTokenConfig() {
        if (isProductionProfileEnabled() && props.isDevFakeTokensEnabled()) {
            throw new IllegalStateException(
                    "dev fake Google Play tokens must be disabled in production"
            );
        }
    }

    private boolean isProductionProfileEnabled() {
        return environment.acceptsProfiles(Profiles.of("prod", "production"));
    }
}
