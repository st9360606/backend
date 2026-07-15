package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.config.PlayReviewAccessProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

@Service
public class PlayReviewAccessService {

    private final PlayReviewAccessProperties properties;
    private final String reviewEmail;

    public PlayReviewAccessService(PlayReviewAccessProperties properties) {
        this.properties = properties;
        this.reviewEmail = normalize(properties.getEmail());
        validateConfiguration();
    }

    public Optional<String> fixedCodeFor(String email) {
        if (!isReviewEmail(email)) {
            return Optional.empty();
        }
        return Optional.of(properties.getCode());
    }

    public boolean isReviewEmail(String email) {
        return properties.isEnabled() && reviewEmail.equals(normalize(email));
    }

    public Duration entitlementValidity() {
        return properties.getEntitlementValidity();
    }

    private void validateConfiguration() {
        if (!properties.isEnabled()) {
            return;
        }
        if (reviewEmail.isBlank()) {
            throw new IllegalStateException("Play review email must be configured when review access is enabled");
        }
        if (properties.getCode() == null || !properties.getCode().matches("\\d{6}")) {
            throw new IllegalStateException("Play review code must contain exactly 6 digits");
        }
        if (properties.getEntitlementValidity() == null
                || properties.getEntitlementValidity().isZero()
                || properties.getEntitlementValidity().isNegative()) {
            throw new IllegalStateException("Play review entitlement validity must be positive");
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
