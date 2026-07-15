package com.caloshape.backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Requires every Backend process to select exactly one real runtime profile.
 * This prevents a deployment with a missing profile from silently inheriting
 * development-only endpoints, fake billing tokens, wildcard CORS, or dev secrets.
 */
public final class RequiredRuntimeProfileEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String PROFILE_MARKER_PROPERTY = "app.runtime.profile-marker";
    private static final Set<String> RUNTIME_PROFILES = Set.of("dev", "prod", "test");

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application
    ) {
        validate(environment);
    }

    static void validate(ConfigurableEnvironment environment) {
        Set<String> activeProfiles = new LinkedHashSet<>(Arrays.asList(environment.getActiveProfiles()));
        Set<String> selectedRuntimeProfiles = new LinkedHashSet<>(activeProfiles);
        selectedRuntimeProfiles.retainAll(RUNTIME_PROFILES);

        if (selectedRuntimeProfiles.size() != 1) {
            throw new IllegalStateException(
                    "Backend requires exactly one active runtime profile: dev, prod, or test"
            );
        }

        String selectedProfile = selectedRuntimeProfiles.iterator().next();
        String profileMarker = environment.getProperty(PROFILE_MARKER_PROPERTY);
        if (!selectedProfile.equals(profileMarker)) {
            throw new IllegalStateException(
                    "Active runtime profile is missing its profile-specific configuration"
            );
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
