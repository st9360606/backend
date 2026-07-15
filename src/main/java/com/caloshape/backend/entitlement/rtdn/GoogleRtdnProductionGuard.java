package com.caloshape.backend.entitlement.rtdn;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@RequiredArgsConstructor
@Configuration
public class GoogleRtdnProductionGuard {

    private final Environment environment;
    private final GoogleRtdnAuthProperties properties;

    @PostConstruct
    void validateProductionConfig() {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            return;
        }
        if (!properties.isOidcEnabled()) {
            throw new IllegalStateException("Google Play RTDN OIDC must be enabled in production");
        }
        if (properties.getOidcAudience() == null || properties.getOidcAudience().isBlank()) {
            throw new IllegalStateException("Google Play RTDN OIDC audience is required in production");
        }
        if (properties.getOidcServiceAccountEmail() == null
                || properties.getOidcServiceAccountEmail().isBlank()) {
            throw new IllegalStateException(
                    "Google Play RTDN push service-account email is required in production"
            );
        }
        if (properties.isLegacyInternalTokenEnabled()) {
            throw new IllegalStateException(
                    "Legacy RTDN internal-token authentication must be disabled in production"
            );
        }
    }
}
