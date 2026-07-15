package com.caloshape.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredRuntimeProfileEnvironmentPostProcessorTest {

    @Test
    void rejectsMissingRuntimeProfile() {
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> RequiredRuntimeProfileEnvironmentPostProcessor.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one active runtime profile");
    }

    @Test
    void rejectsConflictingRuntimeProfiles() {
        MockEnvironment environment = environment("prod", "prod", "dev");

        assertThatThrownBy(() -> RequiredRuntimeProfileEnvironmentPostProcessor.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one active runtime profile");
    }

    @Test
    void rejectsProfileWithoutMatchingConfigurationMarker() {
        MockEnvironment environment = environment("dev", "prod");

        assertThatThrownBy(() -> RequiredRuntimeProfileEnvironmentPostProcessor.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing its profile-specific configuration");
    }

    @Test
    void acceptsOneMarkedRuntimeProfileWithAdditionalNonRuntimeProfile() {
        MockEnvironment environment = environment("prod", "prod", "cloud");

        assertThatCode(() -> RequiredRuntimeProfileEnvironmentPostProcessor.validate(environment))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment environment(
            String marker,
            String... activeProfiles
    ) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        environment.setProperty(
                RequiredRuntimeProfileEnvironmentPostProcessor.PROFILE_MARKER_PROPERTY,
                marker
        );
        return environment;
    }
}
