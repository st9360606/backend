package com.caloshape.backend.entitlement.rtdn;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Objects;

@Configuration
@EnableConfigurationProperties(GoogleRtdnAuthProperties.class)
public class GoogleRtdnOidcConfig {

    @Bean
    GoogleIdTokenVerifier googleRtdnIdTokenVerifier(GoogleRtdnAuthProperties properties) {
        return new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance()
        )
                .setAudience(Collections.singletonList(
                        Objects.requireNonNullElse(properties.getOidcAudience(), "")
                ))
                .build();
    }
}
