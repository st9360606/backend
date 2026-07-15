package com.caloshape.backend.config;

import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthzController {

    private final HealthEndpoint healthEndpoint;

    public HealthzController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/healthz")
    public ResponseEntity<HealthzResponse> health() {
        HealthComponent health = healthEndpoint.health();
        boolean up = Status.UP.equals(health.getStatus());
        HttpStatus status = up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .body(new HealthzResponse(health.getStatus().getCode()));
    }

    public record HealthzResponse(String status) {}
}
