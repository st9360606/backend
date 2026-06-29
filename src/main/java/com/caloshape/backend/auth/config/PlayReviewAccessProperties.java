package com.caloshape.backend.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.auth.play-review")
public class PlayReviewAccessProperties {

    private boolean enabled = false;
    private String email = "";
    private String code = "";
    private Duration entitlementValidity = Duration.ofDays(3650);
}
