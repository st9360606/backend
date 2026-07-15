package com.caloshape.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthzControllerTest {

    @Test
    void returnsOnlyUpStatusWhenDependenciesAreHealthy() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(Health.up().withDetail("database", "sensitive").build());

        ResponseEntity<HealthzController.HealthzResponse> response =
                new HealthzController(endpoint).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new HealthzController.HealthzResponse("UP"));
    }

    @Test
    void returnsServiceUnavailableWithoutDependencyDetailsWhenDown() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(Health.down().withDetail("password", "must-not-leak").build());

        ResponseEntity<HealthzController.HealthzResponse> response =
                new HealthzController(endpoint).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new HealthzController.HealthzResponse("DOWN"));
    }
}
